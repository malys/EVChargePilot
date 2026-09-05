package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.model.EnergyModel
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SpeedWhatIfUnavailable {
    MODEL_NOT_TRAINED,
    NO_MOTORWAY_PORTION,
    MOTORWAY_ENERGY_UNAVAILABLE,
    MOTORWAY_TEMPERATURE_UNAVAILABLE,
    RANGE_BASELINE_UNAVAILABLE,
    NO_REFERENCE_SPEED_IN_ENVELOPE,

    /** A motorway interval has no state-of-charge reading to measure the stretch with. */
    MOTORWAY_CHARGE_UNAVAILABLE,

    /**
     * The gauge moved a step or less across the whole stretch.
     *
     * State of charge is published in whole percent, and one percent of this pack is about
     * four kilometres. Dividing a one-step drop by the distance is dividing by the gauge's
     * own resolution, and the answer would be noise wearing a decimal point.
     */
    MOTORWAY_CHARGE_DROP_TOO_SMALL,
}

/**
 * What a comparison is measured in.
 *
 * Carried by the result rather than by each row, because a screen mixing kilowatt-hours and
 * percent in one table would be read as one quantity in two costumes. One trip, one basis.
 */
enum class SpeedWhatIfBasis { ENERGY_KWH, STATE_OF_CHARGE_PERCENT }

sealed interface SpeedWhatIfResult {
    data class Ready(
        val motorwayDistanceKm: Double,
        val comparisons: List<SpeedWhatIfComparison>,
        val basis: SpeedWhatIfBasis = SpeedWhatIfBasis.ENERGY_KWH,
    ) : SpeedWhatIfResult

    data class Unavailable(val reason: SpeedWhatIfUnavailable) : SpeedWhatIfResult
}

/** One reference speed, in whichever unit the result's basis names. */
data class SpeedWhatIfComparison(
    val referenceSpeedKmh: Int,
    val modelledLow: Double,
    val modelledHigh: Double,
    val deltaLow: Double,
    val deltaHigh: Double,
    val rangeDeltaLowKm: Double,
    val rangeDeltaHighKm: Double,
)

/** Pure post-trip comparison. No route, live prompt, or value outside model evidence. */
object SpeedWhatIfCalculator {
    private data class MotorwayInterval(
        val distanceKm: Double,
        val consumedKwh: Double,
        val outsideTempCelsius: Double,
    )

    private data class ChargeInterval(
        val distanceKm: Double,
        val socDropPercent: Double,
        val outsideTempCelsius: Double,
    )

    /**
     * Power where the vehicle publishes it, state of charge where it does not.
     *
     * The two paths are never blended. A kilowatt-hour measured by integrating power and a
     * percent read off the gauge are different measurements of the same drive, and averaging
     * them would produce a number no instrument took.
     */
    fun calculate(
        trip: StoredTrip,
        model: EnergyModel?,
        socModel: SocConsumptionModel? = null,
        referenceSpeedsKmh: List<Int> = REFERENCE_SPEEDS_KMH,
    ): SpeedWhatIfResult =
        if (model != null && trip.summary.batteryPowerEvidence == model.evidence) {
            fromPower(trip, model, referenceSpeedsKmh)
        } else {
            fromCharge(trip, socModel, referenceSpeedsKmh)
        }

    private fun fromPower(
        trip: StoredTrip,
        model: EnergyModel,
        referenceSpeedsKmh: List<Int>,
    ): SpeedWhatIfResult {
        val samples = trip.samples.orEmpty()
        if (samples.size < 2) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }

        val intervals = ArrayList<MotorwayInterval>()
        var sawMotorway = false
        var sawMissingPower = false
        var sawMissingTemperature = false
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val previousSpeed = previous.speedKmh?.toDouble()
            val currentSpeed = current.speedKmh?.toDouble()
            val durationMs = current.atMs - previous.atMs
            if (previousSpeed == null || currentSpeed == null ||
                !previousSpeed.isFinite() || !currentSpeed.isFinite() ||
                previousSpeed < MOTORWAY_MIN_SPEED_KMH || currentSpeed < MOTORWAY_MIN_SPEED_KMH ||
                durationMs !in 1..MAX_SAMPLE_GAP_MS
            ) {
                continue
            }
            sawMotorway = true
            val previousPower = previous.batteryPowerKw?.toDouble()
            val currentPower = current.batteryPowerKw?.toDouble()
            if (previousPower == null || currentPower == null ||
                !previousPower.isFinite() || !currentPower.isFinite()
            ) {
                sawMissingPower = true
                continue
            }
            val previousTemp = previous.outsideTempCelsius?.toDouble()
            val currentTemp = current.outsideTempCelsius?.toDouble()
            if (previousTemp == null || currentTemp == null ||
                !previousTemp.isFinite() || !currentTemp.isFinite()
            ) {
                sawMissingTemperature = true
                continue
            }
            val hours = durationMs / MILLIS_PER_HOUR
            intervals += MotorwayInterval(
                distanceKm = (previousSpeed + currentSpeed) / 2.0 * hours,
                consumedKwh = (max(0.0, previousPower) + max(0.0, currentPower)) / 2.0 * hours,
                outsideTempCelsius = (previousTemp + currentTemp) / 2.0,
            )
        }
        if (!sawMotorway) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }
        if (sawMissingPower) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_ENERGY_UNAVAILABLE,
            )
        }
        if (sawMissingTemperature) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE,
            )
        }
        val baselineConsumption = trip.summary.averageConsumptionKwhPer100Km
        if (baselineConsumption == null || !baselineConsumption.isFinite() || baselineConsumption <= 0.0) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.RANGE_BASELINE_UNAVAILABLE)
        }

        val actualEnergy = intervals.sumOf { it.consumedKwh }
        val comparisons = referenceSpeedsKmh.distinct().sorted().mapNotNull { referenceSpeed ->
            var modelledLow = 0.0
            var modelledHigh = 0.0
            for (interval in intervals) {
                val prediction = model.predict(
                    referenceSpeed.toDouble(),
                    interval.outsideTempCelsius,
                )
                val low = prediction.bandLow ?: return@mapNotNull null
                val high = prediction.bandHigh ?: return@mapNotNull null
                modelledLow += max(0.0, low) * interval.distanceKm / 100.0
                modelledHigh += max(0.0, high) * interval.distanceKm / 100.0
            }
            val deltaLow = actualEnergy - modelledHigh
            val deltaHigh = actualEnergy - modelledLow
            val rangeA = deltaLow * 100.0 / baselineConsumption
            val rangeB = deltaHigh * 100.0 / baselineConsumption
            SpeedWhatIfComparison(
                referenceSpeedKmh = referenceSpeed,
                modelledLow = modelledLow,
                modelledHigh = modelledHigh,
                deltaLow = min(deltaLow, deltaHigh),
                deltaHigh = max(deltaLow, deltaHigh),
                rangeDeltaLowKm = min(rangeA, rangeB),
                rangeDeltaHighKm = max(rangeA, rangeB),
            )
        }
        if (comparisons.isEmpty()) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE,
            )
        }
        return SpeedWhatIfResult.Ready(intervals.sumOf { it.distanceKm }, comparisons)
    }

    /**
     * The same question answered from the gauge the driver watched fall.
     *
     * CP-052's fit is in percent per 100 km, so nothing here converts anything: the stretch is
     * measured in percent, the model predicts percent, and the equivalent distance comes from
     * this trip's own percent per kilometre. No pack capacity appears, which is the only reason
     * this works on a vehicle that publishes no battery power.
     */
    private fun fromCharge(
        trip: StoredTrip,
        model: SocConsumptionModel?,
        referenceSpeedsKmh: List<Int>,
    ): SpeedWhatIfResult {
        // A distance converted by a rule no longer believed is not this trip's distance, and
        // the fit carries the rule it was trained under precisely so this can be checked.
        if (model == null || trip.summary.speedEvidence != model.speedEvidence) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.MODEL_NOT_TRAINED)
        }
        val samples = trip.samples.orEmpty()
        if (samples.size < 2) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }

        val intervals = ArrayList<ChargeInterval>()
        var sawMotorway = false
        var sawMissingCharge = false
        var sawMissingTemperature = false
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val previousSpeed = previous.speedKmh?.toDouble()
            val currentSpeed = current.speedKmh?.toDouble()
            val durationMs = current.atMs - previous.atMs
            if (previousSpeed == null || currentSpeed == null ||
                !previousSpeed.isFinite() || !currentSpeed.isFinite() ||
                previousSpeed < MOTORWAY_MIN_SPEED_KMH || currentSpeed < MOTORWAY_MIN_SPEED_KMH ||
                durationMs !in 1..MAX_SAMPLE_GAP_MS
            ) {
                continue
            }
            sawMotorway = true
            val previousSoc = previous.socPercent?.toDouble()
            val currentSoc = current.socPercent?.toDouble()
            if (previousSoc == null || currentSoc == null ||
                !previousSoc.isFinite() || !currentSoc.isFinite()
            ) {
                sawMissingCharge = true
                continue
            }
            val previousTemp = previous.outsideTempCelsius?.toDouble()
            val currentTemp = current.outsideTempCelsius?.toDouble()
            if (previousTemp == null || currentTemp == null ||
                !previousTemp.isFinite() || !currentTemp.isFinite()
            ) {
                sawMissingTemperature = true
                continue
            }
            val hours = durationMs / MILLIS_PER_HOUR
            intervals += ChargeInterval(
                distanceKm = (previousSpeed + currentSpeed) / 2.0 * hours,
                // Not clamped at zero: regeneration genuinely puts charge back, and throwing
                // that away would report a stretch as costlier than it was.
                socDropPercent = previousSoc - currentSoc,
                outsideTempCelsius = (previousTemp + currentTemp) / 2.0,
            )
        }
        if (!sawMotorway) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }
        if (sawMissingCharge) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_CHARGE_UNAVAILABLE,
            )
        }
        if (sawMissingTemperature) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE,
            )
        }
        val actualPercent = intervals.sumOf { it.socDropPercent }
        val distanceKm = intervals.sumOf { it.distanceKm }
        if (abs(actualPercent) < MIN_MOTORWAY_SOC_DROP_PERCENT || distanceKm <= 0.0) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_CHARGE_DROP_TOO_SMALL,
            )
        }
        val percentPerKm = tripPercentPerKm(trip)
            ?: return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.RANGE_BASELINE_UNAVAILABLE,
            )

        val comparisons = referenceSpeedsKmh.distinct().sorted().mapNotNull { referenceSpeed ->
            var modelledLow = 0.0
            var modelledHigh = 0.0
            for (interval in intervals) {
                val prediction = model.predict(
                    referenceSpeed.toDouble(),
                    interval.outsideTempCelsius,
                )
                val low = prediction.bandLow ?: return@mapNotNull null
                val high = prediction.bandHigh ?: return@mapNotNull null
                modelledLow += max(0.0, low) * interval.distanceKm / 100.0
                modelledHigh += max(0.0, high) * interval.distanceKm / 100.0
            }
            val deltaLow = actualPercent - modelledHigh
            val deltaHigh = actualPercent - modelledLow
            SpeedWhatIfComparison(
                referenceSpeedKmh = referenceSpeed,
                modelledLow = modelledLow,
                modelledHigh = modelledHigh,
                deltaLow = min(deltaLow, deltaHigh),
                deltaHigh = max(deltaLow, deltaHigh),
                rangeDeltaLowKm = min(deltaLow, deltaHigh) / percentPerKm,
                rangeDeltaHighKm = max(deltaLow, deltaHigh) / percentPerKm,
            )
        }
        if (comparisons.isEmpty()) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE,
            )
        }
        return SpeedWhatIfResult.Ready(
            distanceKm,
            comparisons,
            SpeedWhatIfBasis.STATE_OF_CHARGE_PERCENT,
        )
    }

    /** What this trip spent per kilometre, which is what turns a saving back into distance. */
    private fun tripPercentPerKm(trip: StoredTrip): Double? {
        val start = trip.summary.startSocPercent?.toDouble() ?: return null
        val end = trip.summary.endSocPercent?.toDouble() ?: return null
        val distanceKm = trip.summary.recordedDistanceKm ?: return null
        if (distanceKm <= 0.0) return null
        return ((start - end) / distanceKm).takeIf { it.isFinite() && it > 0.0 }
    }

    val REFERENCE_SPEEDS_KMH = listOf(90, 100, 110, 120, 130)
    private const val MOTORWAY_MIN_SPEED_KMH = 90.0

    /** Two steps of a gauge published in whole percent, matching `SocConsumptionFitter`. */
    private const val MIN_MOTORWAY_SOC_DROP_PERCENT = 2.0
    private const val MAX_SAMPLE_GAP_MS = 120_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0
}
