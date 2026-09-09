package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import kotlin.math.abs
import kotlin.math.sqrt

enum class ChargeAttributionUnavailable {
    /** No charge fit, or one trained under a distance conversion this trip does not share. */
    MODEL_NOT_TRAINED,

    /** Nothing the model could speak about: no interval with speed, temperature and charge. */
    NO_MODELLED_INTERVAL,

    /**
     * The gauge moved two steps or less across everything the model covered.
     *
     * State of charge is published in whole percent. Splitting a two-point drop between
     * driving and everything else is splitting the gauge's own resolution.
     */
    CHARGE_DROP_TOO_SMALL,
}

/** Charge, with the uncertainty needed to render it honestly. Never rendered without it. */
data class ChargeEstimate(val percent: Double, val uncertaintyPercent: Double) {
    init {
        require(percent.isFinite())
        require(uncertaintyPercent.isFinite() && uncertaintyPercent >= 0.0)
    }

    val bandLowPercent: Double get() = percent - uncertaintyPercent
    val bandHighPercent: Double get() = percent + uncertaintyPercent
}

data class ChargeResidual(
    val context: ResidualContext,
    val estimate: ChargeEstimate,
    val finding: ResidualFinding,
    /** Contiguous stretches in this context: how many times the gauge was read for it. */
    val runCount: Int,
    val distanceKm: Double,
)

data class ChargeAttribution(
    val distanceKm: Double,
    /** Measured on the gauge across the modelled stretches. */
    val measuredDropPercent: Double,
    /** Charge this trip spent where no modelled interval covered it. Never hidden. */
    val uncoveredDropPercent: Double,
    val modelledDriving: ChargeEstimate,
    val residuals: List<ChargeResidual>,
    /** This trip's own rate, which is what turns a percent back into kilometres. */
    val percentPerKm: Double,
)

sealed interface ChargeAttributionResult {
    data class Ready(val attribution: ChargeAttribution) : ChargeAttributionResult
    data class Unavailable(val reason: ChargeAttributionUnavailable) : ChargeAttributionResult
}

/**
 * The post-trip breakdown for a car that publishes no battery power, measured on the gauge.
 *
 * `EnergyAttributionCalculator` splits kilowatt-hours and needs a per-sample battery power to
 * do it. SWI68 publishes none, so that screen could only ever refuse here — which is what the
 * driver saw. CP-052's fit answers the same question in percent of charge per 100 km, so the
 * split can be made in the unit the car actually publishes: no pack capacity is assumed, and
 * nothing is converted.
 *
 * **What a residual means here is not what it means there.** The kWh model is fitted against
 * measured battery power; this one is fitted against past trips of this driver, climate load
 * included. Its prediction is therefore what a *typical* trip of theirs costs, and the residual
 * is this trip against that typical one — not the climate system's own consumption. A climate
 * -active residual says the heating or cooling on this drive cost more than the driver's usual
 * mix, and that is all it says. The screen says so too; a number this easy to over-read must
 * carry its own caveat.
 *
 * The gauge's resolution is carried, not hidden. Every contiguous stretch is measured by two
 * whole-percent readings, so each one contributes a step of quantisation to the band, added in
 * quadrature across stretches because the readings are independent. A residual that does not
 * clear that band is reported as indistinguishable from zero rather than as a small finding.
 */
object ChargeAttributionCalculator {
    fun calculate(trip: StoredTrip, model: SocConsumptionModel?): ChargeAttributionResult {
        // The fit carries the distance conversion it was trained under precisely so a trip
        // recorded under another one cannot be measured against it.
        if (model == null || trip.summary.speedEvidence != model.speedEvidence) {
            return ChargeAttributionResult.Unavailable(
                ChargeAttributionUnavailable.MODEL_NOT_TRAINED,
            )
        }
        val samples = trip.samples.orEmpty()
        if (samples.size < 2) {
            return ChargeAttributionResult.Unavailable(
                ChargeAttributionUnavailable.NO_MODELLED_INTERVAL,
            )
        }

        val groups = ResidualContext.entries.associateWith { ContextAccumulator() }
        var covered = false
        var previousContext: ResidualContext? = null
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val interval = interval(previous, current, model)
            if (interval == null) {
                // Standstill, a hole in the track, a missing signal, or a speed and temperature
                // the fit never saw: the charge it cost stays in the uncovered row below.
                covered = false
                continue
            }
            val context = climateContext(previous, current)
            groups.getValue(context).add(interval, newRun = !covered || context != previousContext)
            covered = true
            previousContext = context
        }

        val distanceKm = groups.values.sumOf { it.distanceKm }
        val measuredDrop = groups.values.sumOf { it.measuredDropPercent }
        if (groups.values.sumOf { it.runCount } == 0 || distanceKm <= 0.0) {
            return ChargeAttributionResult.Unavailable(
                ChargeAttributionUnavailable.NO_MODELLED_INTERVAL,
            )
        }
        if (abs(measuredDrop) < MIN_ATTRIBUTABLE_DROP_PERCENT) {
            return ChargeAttributionResult.Unavailable(
                ChargeAttributionUnavailable.CHARGE_DROP_TOO_SMALL,
            )
        }
        val percentPerKm = (measuredDrop / distanceKm).takeIf { it.isFinite() && it > 0.0 }
            ?: return ChargeAttributionResult.Unavailable(
                ChargeAttributionUnavailable.CHARGE_DROP_TOO_SMALL,
            )

        return ChargeAttributionResult.Ready(
            ChargeAttribution(
                distanceKm = distanceKm,
                measuredDropPercent = measuredDrop,
                uncoveredDropPercent = tripDropPercent(samples) - measuredDrop,
                modelledDriving = ChargeEstimate(
                    groups.values.sumOf { it.modelledPercent },
                    groups.values.sumOf { it.modelledUncertaintyPercent },
                ),
                residuals = ResidualContext.entries.mapNotNull { groups.getValue(it).build(it) },
                percentPerKm = percentPerKm,
            ),
        )
    }

    private data class ModelledInterval(
        val distanceKm: Double,
        val modelledPercent: Double,
        val modelledUncertaintyPercent: Double,
        val measuredDropPercent: Double,
    )

    private fun interval(
        previous: TripSample,
        current: TripSample,
        model: SocConsumptionModel,
    ): ModelledInterval? {
        val durationMs = current.atMs - previous.atMs
        if (durationMs !in 1..MAX_SAMPLE_GAP_MS) return null
        val previousSpeed = previous.speedKmh?.toDouble() ?: return null
        val currentSpeed = current.speedKmh?.toDouble() ?: return null
        val previousSoc = previous.socPercent?.toDouble() ?: return null
        val currentSoc = current.socPercent?.toDouble() ?: return null
        val previousTemp = previous.outsideTempCelsius?.toDouble() ?: return null
        val currentTemp = current.outsideTempCelsius?.toDouble() ?: return null
        if (!previousSpeed.isFinite() || !currentSpeed.isFinite() ||
            !previousSoc.isFinite() || !currentSoc.isFinite() ||
            !previousTemp.isFinite() || !currentTemp.isFinite()
        ) {
            return null
        }
        val hours = durationMs / MILLIS_PER_HOUR
        val distanceKm = (previousSpeed + currentSpeed) / 2.0 * hours
        if (!distanceKm.isFinite() || distanceKm <= 0.0) return null
        val prediction = model.predict(
            (previousSpeed + currentSpeed) / 2.0,
            (previousTemp + currentTemp) / 2.0,
        )
        val value = prediction.value ?: return null
        val uncertainty = prediction.uncertainty ?: return null
        return ModelledInterval(
            distanceKm = distanceKm,
            modelledPercent = value * distanceKm / 100.0,
            modelledUncertaintyPercent = uncertainty * distanceKm / 100.0,
            // Not clamped at zero: regeneration puts charge back, and dropping that would
            // report the stretch as costlier than the gauge said it was.
            measuredDropPercent = previousSoc - currentSoc,
        )
    }

    /** The whole record's drop, so charge outside the modelled stretches stays visible. */
    private fun tripDropPercent(samples: List<TripSample>): Double {
        val readings = samples.mapNotNull { it.socPercent?.toDouble()?.takeIf(Double::isFinite) }
        if (readings.size < 2) return 0.0
        return readings.first() - readings.last()
    }

    /**
     * Same rule as `EnergyAttributionCalculator`'s: any sign of climate makes the stretch
     * climate-active, an explicit off on both ends makes it inactive, silence stays unknown.
     * Restated here rather than shared because the library's copy is private and EVHardware is
     * a submodule of other applications; if a third caller appears, it should move there.
     */
    private fun climateContext(previous: TripSample, current: TripSample): ResidualContext {
        val states = listOf(climateActive(previous), climateActive(current))
        return when {
            states.any { it == true } -> ResidualContext.CLIMATE_ACTIVE
            states.all { it == false } -> ResidualContext.CLIMATE_INACTIVE
            else -> ResidualContext.CLIMATE_UNKNOWN
        }
    }

    private fun climateActive(sample: TripSample): Boolean? = when {
        sample.climatePowerOn == true || sample.climateAcOn == true ||
            sample.climateFanLevel?.let { it > 0 } == true -> true
        sample.climatePowerOn == false -> false
        else -> null
    }

    private class ContextAccumulator {
        var distanceKm = 0.0
        var modelledPercent = 0.0
        var modelledUncertaintyPercent = 0.0
        var measuredDropPercent = 0.0
        var runCount = 0

        fun add(interval: ModelledInterval, newRun: Boolean) {
            distanceKm += interval.distanceKm
            modelledPercent += interval.modelledPercent
            modelledUncertaintyPercent += interval.modelledUncertaintyPercent
            measuredDropPercent += interval.measuredDropPercent
            if (newRun) runCount++
        }

        fun build(context: ResidualContext): ChargeResidual? {
            if (runCount == 0) return null
            val value = measuredDropPercent - modelledPercent
            // Two whole-percent readings bound each stretch, and stretches are independent.
            val uncertainty = modelledUncertaintyPercent +
                GAUGE_STEP_PERCENT * sqrt(runCount.toDouble())
            val estimate = ChargeEstimate(value, uncertainty)
            return ChargeResidual(
                context = context,
                estimate = estimate,
                finding = when {
                    value < 0.0 -> ResidualFinding.NEGATIVE_MODEL_ERROR
                    value <= uncertainty -> ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO
                    else -> ResidualFinding.DISTINGUISHABLE
                },
                runCount = runCount,
                distanceKm = distanceKm,
            )
        }
    }

    /** Two steps of a gauge published in whole percent, matching `SocConsumptionFitter`. */
    private const val MIN_ATTRIBUTABLE_DROP_PERCENT = 2.0
    private const val GAUGE_STEP_PERCENT = 1.0
    private const val MAX_SAMPLE_GAP_MS = 120_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0
}
