package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EcoBand
import com.evsuite.hardware.telemetry.EcoDrivingMonitor
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import com.evsuite.hardware.telemetry.model.EnergyAttributionCalculator
import com.evsuite.hardware.telemetry.model.EnergyAttributionResult
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import java.io.File
import java.util.Locale

/** One thing about this drive that was measured, with the number that measured it. */
sealed interface EcoFinding {
    /**
     * What the trip's own motorway intervals say a lower reference speed would have cost.
     *
     * The saving stays in the basis the what-if produced it in — kilowatt-hours where the car
     * publishes power, percent of charge where it does not — and is never converted across that
     * boundary. One trip, one unit.
     */
    data class MotorwaySpeed(
        val referenceSpeedKmh: Int,
        val savingLow: Double,
        val savingHigh: Double,
        val basis: SpeedWhatIfBasis,
        val motorwayDistanceKm: Double,
    ) : EcoFinding

    /** The cabin's fitted share of this trip. Only ever a fit, never a fan level. */
    data class Cabin(
        val kwh: Double,
        val uncertaintyKwh: Double,
        val sharePercent: Double,
    ) : EcoFinding

    /** The share of moving time spent accelerating hard, from the track's speed channel. */
    data class Steadiness(val harshSharePercent: Double) : EcoFinding
}

/** The band as a word. One mapping for the live tile and the trip review both. */
fun ecoBandRes(band: EcoBand): Int = when (band) {
    EcoBand.BETTER -> R.string.eco_band_better
    EcoBand.TYPICAL -> R.string.eco_band_typical
    EcoBand.WORSE -> R.string.eco_band_worse
}

sealed interface EcoTripReview {
    data class Ready(val verdict: EcoVerdict, val findings: List<EcoFinding>) : EcoTripReview

    /** Nothing measurable, and which silence it was. The normal answer for a first short trip. */
    data class Unavailable(val reason: UnavailableReason) : EcoTripReview
}

/**
 * Was that drive an efficient one, and what would have made it one.
 *
 * **It computes almost nothing.** The band is [EcoDrivingMonitor] replayed over the stored track,
 * the motorway saving is the what-if the trip screen already offers behind a button, and the
 * cabin share is CP-034's attribution. This file composes three answers and ranks them; every
 * number in it was produced by something else, which is why it lives in the app rather than in
 * EVHardware and owns no physics of its own.
 *
 * **Ranked by how strong the evidence is, not by a common cost.** A saving in kilowatt-hours, a
 * fitted cabin share and a percentage of a drive have no exchange rate, and inventing one to sort
 * them would invent the most important of the three. Order is therefore fixed and stated: the
 * measured saving first, the fitted share next, the style share last.
 *
 * **What it refuses.** A model that has not trained, an attribution that could not reconcile, a
 * what-if with no motorway in the trip, a track that was never recorded — each drops its own
 * finding and none of them invents a replacement. When nothing is left and the band is refused
 * too, the review is [EcoTripReview.Unavailable] with the band's own reason.
 */
object EcoTripReviewer {

    /** Three is what fits under the readouts, and the fourth would be read by nobody. */
    const val MAX_FINDINGS = 3

    /** Below this the cabin is inside the fit's own error and saying so would be noise. */
    const val MIN_CABIN_SHARE_PERCENT = 5.0

    fun review(
        trip: StoredTrip,
        socModel: SocConsumptionModel?,
        attribution: EnergyAttributionResult?,
        whatIf: SpeedWhatIfResult?,
        generation: FirmwareInfo.Gen = FirmwareInfo.getGeneration(),
    ): EcoTripReview {
        val monitor = EcoDrivingMonitor.forReplay().replay(trip.samples.orEmpty())
        val verdict = monitor.verdict(
            socModel,
            trip.summary,
            meanOutsideTempCelsius(trip),
            generation,
        )
        val findings = listOfNotNull(
            motorway(whatIf),
            cabin(attribution),
            steadiness(monitor),
        ).take(MAX_FINDINGS)
        if (verdict.band.value == null && findings.isEmpty()) {
            return EcoTripReview.Unavailable(
                verdict.band.reason ?: UnavailableReason.INSUFFICIENT_SAMPLES
            )
        }
        return EcoTripReview.Ready(verdict, findings)
    }

    /**
     * The mean of the temperatures the track recorded, which is what the fit was trained on.
     *
     * A single reading would be the temperature at one moment of a drive that may have crossed a
     * mountain; `SocConsumptionFitter` averages its segments, and the prediction is only about
     * the same drive if the question is asked the same way.
     */
    private fun meanOutsideTempCelsius(trip: StoredTrip): Float? {
        val temps = trip.samples.orEmpty().mapNotNull { it.outsideTempCelsius }
        return if (temps.isEmpty()) null else temps.average().toFloat()
    }

    /** The review as text, for the export and the diagnostic bundle. */
    fun describe(review: EcoTripReview): List<String> = when (review) {
        is EcoTripReview.Unavailable -> listOf("review=unavailable(${review.reason.name})")
        is EcoTripReview.Ready -> review.verdict.describe() + review.findings.map(::describe)
    }

    private fun describe(finding: EcoFinding): String = when (finding) {
        is EcoFinding.MotorwaySpeed -> "finding=MOTORWAY_SPEED" +
            " reference_speed_kmh=${finding.referenceSpeedKmh}" +
            " saving=${format(finding.savingLow)}..${format(finding.savingHigh)}" +
            " basis=${finding.basis.name}" +
            " motorway_km=${format(finding.motorwayDistanceKm)}"
        is EcoFinding.Cabin -> "finding=CABIN" +
            " kwh=${format(finding.kwh)}" +
            " uncertainty_kwh=${format(finding.uncertaintyKwh)}" +
            " share_percent=${format(finding.sharePercent)}"
        is EcoFinding.Steadiness ->
            "finding=STEADINESS harsh_share_percent=${format(finding.harshSharePercent)}"
    }

    /**
     * The reference speed with the largest saving this trip would have had at it.
     *
     * The band's lower edge is what is compared, so a comparison that could be nothing is never
     * chosen over one that is certainly something.
     */
    private fun motorway(whatIf: SpeedWhatIfResult?): EcoFinding.MotorwaySpeed? {
        val ready = whatIf as? SpeedWhatIfResult.Ready ?: return null
        val best = ready.comparisons
            .filter { it.deltaLow > 0.0 }
            .maxByOrNull { it.deltaLow }
            ?: return null
        return EcoFinding.MotorwaySpeed(
            referenceSpeedKmh = best.referenceSpeedKmh,
            savingLow = best.deltaLow,
            savingHigh = best.deltaHigh,
            basis = ready.basis,
            motorwayDistanceKm = ready.motorwayDistanceKm,
        )
    }

    /**
     * The cabin, and only where the attribution could tell it apart from its own error.
     *
     * A residual recorded with the climate system off is model error or another auxiliary load —
     * `EnergyAttribution` is explicit that it is not climate energy — so only `CLIMATE_ACTIVE`
     * residuals that the fit found `DISTINGUISHABLE` are counted here.
     */
    private fun cabin(attribution: EnergyAttributionResult?): EcoFinding.Cabin? {
        val ready = attribution as? EnergyAttributionResult.Ready ?: return null
        val climate = ready.attribution.residuals.filter {
            it.context == ResidualContext.CLIMATE_ACTIVE &&
                it.finding == ResidualFinding.DISTINGUISHABLE
        }
        if (climate.isEmpty()) return null
        val kwh = climate.sumOf { it.estimate.valueKwh }
        val total = ready.attribution.totalConsumedKwh.value ?: return null
        if (total <= 0.0) return null
        val share = kwh / total * 100.0
        if (share < MIN_CABIN_SHARE_PERCENT) return null
        return EcoFinding.Cabin(
            kwh = kwh,
            uncertaintyKwh = climate.sumOf { it.estimate.uncertaintyKwh },
            sharePercent = share,
        )
    }

    /**
     * Whatever the share, once the track holds a minute of movement. A calm drive is a result
     * too: below the live advice threshold it used to drop out, and a calm drive with no fitted
     * model yet then read "nothing measurable" although its whole track had been measured.
     */
    private fun steadiness(monitor: EcoDrivingMonitor): EcoFinding.Steadiness? =
        monitor.harshSharePercent()?.let(EcoFinding::Steadiness)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

/** The attribution and the review of every trip, from the fits the whole history supports. */
data class ReviewedHistory(
    val attributions: Map<Long, EnergyAttributionResult>,
    val reviews: Map<Long, EcoTripReview>,
)

/**
 * Reviews a whole ledger against one pair of fits. Reads the model file: call off the main thread.
 *
 * The screen, the export and the diagnostic bundle all answer from this, so the three cannot
 * disagree about a trip — a review that read differently in a shared file than on the screen that
 * produced it would be worse than no review.
 */
fun reviewHistory(filesDir: File, trips: List<StoredTrip>): ReviewedHistory {
    val evidence = trips.firstNotNullOfOrNull { it.summary.batteryPowerEvidence }
    val model = LocalEnergyModel.loadOrTrain(filesDir, trips, evidence)
    val socModel = (SocConsumptionFitter().fit(trips) as? SocConsumptionFitResult.Ready)?.model
    val attributions = trips.associate { trip ->
        trip.summary.startedAtMs to EnergyAttributionCalculator.calculate(trip, model)
    }
    val reviews = trips.associate { trip ->
        trip.summary.startedAtMs to EcoTripReviewer.review(
            trip,
            socModel,
            attributions[trip.summary.startedAtMs],
            SpeedWhatIfCalculator.calculate(trip, model, socModel),
        )
    }
    return ReviewedHistory(attributions, reviews)
}
