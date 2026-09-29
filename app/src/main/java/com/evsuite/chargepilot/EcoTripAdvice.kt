package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.telemetry.DrivingStress
import com.evsuite.hardware.telemetry.EcoBand
import com.evsuite.hardware.telemetry.EcoDrivingMonitor
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.TripSampleTrack
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moving time, and the part of it spent accelerating or braking hard, from a stored track.
 *
 * [EcoDrivingMonitor]'s steadiness counts acceleration only, which is the live lever. Braking is
 * the other half of the same habit: every hard stop throws away what regeneration could not take
 * back. Both use the monitor's own threshold and the replay's gap bound, so a track reads the same
 * here as it does on the trip screen.
 */
data class DrivingStyle(
    val movingMs: Long,
    val harshAccelerationMs: Long,
    val harshBrakingMs: Long,
) {
    val harshMs: Long get() = harshAccelerationMs + harshBrakingMs

    /** Null below the monitor's minimum window: a minute of driving says nothing about a habit. */
    val harshSharePercent: Double?
        get() = if (movingMs >= EcoDrivingMonitor.MIN_LEVER_WINDOW_MS) {
            harshMs * 100.0 / movingMs
        } else null

    operator fun plus(other: DrivingStyle) = DrivingStyle(
        movingMs + other.movingMs,
        harshAccelerationMs + other.harshAccelerationMs,
        harshBrakingMs + other.harshBrakingMs,
    )

    companion object {
        val NONE = DrivingStyle(0L, 0L, 0L)
        private const val MAX_GAP_MS = TripSampleTrack.DEFAULT_INTERVAL_MS * 2
        private const val KMH_PER_MS = 3.6

        fun measure(samples: List<TripSample>): DrivingStyle {
            var moving = 0L
            var accelerating = 0L
            var braking = 0L
            samples.zipWithNext { a, b ->
                val gapMs = b.atMs - a.atMs
                if (gapMs !in 1..MAX_GAP_MS) return@zipWithNext
                val from = a.speedKmh ?: return@zipWithNext
                val to = b.speedKmh ?: return@zipWithNext
                if (from <= 0f && to <= 0f) return@zipWithNext
                moving += gapMs
                val ms2 = (to - from) / KMH_PER_MS / (gapMs / 1000.0)
                if (ms2 >= EcoDrivingMonitor.HARSH_ACCELERATION_MS2) accelerating += gapMs
                if (ms2 <= -EcoDrivingMonitor.HARSH_ACCELERATION_MS2) braking += gapMs
            }
            return DrivingStyle(moving, accelerating, braking)
        }
    }
}

/** What a drive heavier than the driver's own average cost over it. */
data class TripSaving(
    /** Share of this drive's consumption above the expectation. */
    val percent: Double,
    /** The same share of the trip's net energy; null where the trip has no energy figure. */
    val kwh: Double?,
    /** The same share of the charge the trip used, which the gauge always gives. */
    val socPoints: Double?,
)

/** One thing that would have made the drive cheaper, each with the number that earned it. */
sealed interface EcoTip {
    data class Smoother(val harshMinutes: Int, val sharePercent: Int) : EcoTip
    data class SlowerMotorway(val finding: EcoFinding.MotorwaySpeed) : EcoTip

    /** [current] null means the mode could not be read, and the line says "if not already". */
    data class EcoMode(val current: DriveMode?) : EcoTip
    data class SnowMode(val outsideTempCelsius: Int) : EcoTip
    data class Cabin(val finding: EcoFinding.Cabin) : EcoTip
    data class Windows(val finding: EcoFinding.Windows) : EcoTip
}

/** The end-of-trip advice. [band] null when the fit refused; the tips may still stand. */
data class TripAdvice(
    val band: EcoBand?,
    val deltaPercent: Double?,
    val saving: TripSaving?,
    val tips: List<EcoTip>,
)

/** What the recent trips say together, for the overview under the trip diagrams. */
data class HistoryAdvice(
    val reviewedTrips: Int,
    val heavierTrips: Int,
    val excessKwh: Double?,
    val excessSocPoints: Double?,
    val style: EcoTip.Smoother?,
    val motorwaySaving: Double?,
    val motorwayBasis: SpeedWhatIfBasis?,
    val motorwayTrips: Int,
    val cabinMeanPercent: Double?,
    val windowsMeanPercent: Double?,
) {
    val hasAdvice: Boolean
        get() = heavierTrips > 0 || style != null || motorwaySaving != null ||
            cabinMeanPercent != null || windowsMeanPercent != null
}

/**
 * Turns a trip review into what the driver is told when the trip closes in P, and the recent
 * history into general advice.
 *
 * Every number comes from something already computed — the fit's expectation, the what-if, the
 * attribution, the track — and every tip is dropped rather than guessed when its source refused.
 * The saving is measured against the driver's *own* expectation for that speed and temperature:
 * "you could have saved X %" means "back to your usual", never a figure from a brochure.
 */
object EcoTripAdvice {

    /** Below this, snow mode's softer throttle is not worth naming. */
    const val SNOW_MAX_OUTSIDE_CELSIUS = 3.0

    /** The overview reads this many recent trips; older habits are not today's advice. */
    const val HISTORY_TRIPS = 20

    const val MAX_TIPS = 4

    fun advise(trip: StoredTrip, review: EcoTripReview?, driveMode: DriveMode?): TripAdvice {
        val ready = review as? EcoTripReview.Ready
        val verdict = ready?.verdict
        val band = verdict?.band?.value
        val smoother = smoother(DrivingStyle.measure(trip.samples.orEmpty()))
        val findings = ready?.findings.orEmpty()
        val tips = buildList {
            if (smoother != null) add(smoother)
            findings.filterIsInstance<EcoFinding.MotorwaySpeed>().forEach { add(EcoTip.SlowerMotorway(it)) }
            val softer = smoother != null || band == EcoBand.WORSE
            if (softer && driveMode != DriveMode.ECO && driveMode != DriveMode.SNOW) {
                add(EcoTip.EcoMode(driveMode))
            }
            val cold = meanOutsideCelsius(trip)
            if (smoother != null && driveMode != DriveMode.SNOW && cold != null &&
                cold <= SNOW_MAX_OUTSIDE_CELSIUS
            ) add(EcoTip.SnowMode(cold.roundToInt()))
            findings.filterIsInstance<EcoFinding.Cabin>().forEach { add(EcoTip.Cabin(it)) }
            findings.filterIsInstance<EcoFinding.Windows>().forEach { add(EcoTip.Windows(it)) }
        }.take(MAX_TIPS)
        return TripAdvice(
            band = band,
            deltaPercent = verdict?.deltaPercent,
            saving = verdict?.let { saving(it, trip.summary) },
            tips = tips,
        )
    }

    /** Only a heavier drive has something to save, and only down to the expectation. */
    fun saving(verdict: EcoVerdict, summary: EnergyTripSummary): TripSaving? {
        if (verdict.band.value != EcoBand.WORSE) return null
        val observed = verdict.observedPercentPer100Km ?: return null
        val expected = verdict.expectedPercentPer100Km ?: return null
        if (observed <= 0.0 || observed <= expected) return null
        val share = (observed - expected) / observed
        val drop = summary.startSocPercent?.let { start ->
            summary.endSocPercent?.let { end -> (start - end).toDouble() }
        }?.takeIf { it > 0.0 }
        return TripSaving(
            percent = share * 100.0,
            kwh = TripOverview.netKwh(summary)?.takeIf { it > 0.0 }?.times(share),
            socPoints = drop?.times(share),
        )
    }

    fun smoother(style: DrivingStyle): EcoTip.Smoother? {
        val share = style.harshSharePercent ?: return null
        if (share < EcoDrivingMonitor.MIN_HARSH_SHARE_PERCENT) return null
        val minutes = (style.harshMs / 60_000.0).roundToInt().coerceAtLeast(1)
        return EcoTip.Smoother(minutes, share.roundToInt())
    }

    /**
     * The newest [HISTORY_TRIPS] trips, together. Heavier trips add their excess; habits are summed
     * over time rather than averaged per trip, so a long motorway run weighs what it lasted.
     */
    fun history(
        tripsNewestFirst: List<StoredTrip>,
        reviews: Map<Long, EcoTripReview>,
    ): HistoryAdvice {
        val recent = tripsNewestFirst.take(HISTORY_TRIPS)
        var reviewed = 0
        var heavier = 0
        val excessKwh = ArrayList<Double>()
        val excessSoc = ArrayList<Double>()
        var style = DrivingStyle.NONE
        val motorway = ArrayList<EcoFinding.MotorwaySpeed>()
        val cabin = ArrayList<Double>()
        val windows = ArrayList<Double>()
        for (trip in recent) {
            style += DrivingStyle.measure(trip.samples.orEmpty())
            val ready = reviews[trip.summary.startedAtMs] as? EcoTripReview.Ready ?: continue
            if (ready.verdict.band.value != null) reviewed++
            saving(ready.verdict, trip.summary)?.let { saving ->
                heavier++
                saving.kwh?.let(excessKwh::add)
                saving.socPoints?.let(excessSoc::add)
            }
            ready.findings.forEach { finding ->
                when (finding) {
                    is EcoFinding.MotorwaySpeed -> motorway += finding
                    is EcoFinding.Cabin -> cabin += finding.sharePercent
                    is EcoFinding.Windows -> windows += finding.sharePercent
                    is EcoFinding.Steadiness -> Unit
                }
            }
        }
        // One unit per sentence: kWh wherever the car published power, otherwise charge points.
        val basis = if (motorway.any { it.basis == SpeedWhatIfBasis.ENERGY_KWH }) {
            SpeedWhatIfBasis.ENERGY_KWH
        } else motorway.firstOrNull()?.basis
        val sameBasis = motorway.filter { it.basis == basis }
        return HistoryAdvice(
            reviewedTrips = reviewed,
            heavierTrips = heavier,
            excessKwh = excessKwh.takeIf { it.isNotEmpty() }?.sum(),
            excessSocPoints = excessSoc.takeIf { it.isNotEmpty() }?.sum(),
            style = smoother(style),
            motorwaySaving = sameBasis.takeIf { it.isNotEmpty() }?.sumOf { it.savingLow },
            motorwayBasis = basis,
            motorwayTrips = sameBasis.size,
            // One cabin-heavy trip is weather; two are a habit worth naming.
            cabinMeanPercent = cabin.takeIf { it.size >= 2 }?.average(),
            windowsMeanPercent = windows.takeIf { it.size >= 2 }?.average(),
        )
    }

    private fun meanOutsideCelsius(trip: StoredTrip): Double? =
        trip.samples.orEmpty().mapNotNull { it.outsideTempCelsius }
            .takeIf { it.isNotEmpty() }?.average()
}

/**
 * The advice in words. Consequences with numbers, in the conditional — the driver is told what a
 * change would have given, never ordered to make it.
 */
class EcoAdviceText(context: Context) {

    // Not the application context: the service hands in one carrying the app's own language.
    private val app = context

    /** The line spoken when the trip closes in P. */
    fun spoken(advice: TripAdvice): String {
        val saving = advice.saving
        val sentences = ArrayList<String>()
        val rest: List<EcoTip>
        if (saving != null) {
            val clauses = advice.tips.mapNotNull(::clause)
            val amount = saving.kwh?.let { app.getString(R.string.eco_amount_kwh, it) }
                ?: saving.socPoints?.let { app.getString(R.string.eco_amount_soc, it) }
            val head = if (amount != null) {
                app.getString(R.string.eco_trip_saving, saving.percent.roundToInt(), amount)
            } else {
                app.getString(R.string.eco_trip_saving_percent, saving.percent.roundToInt())
            }
            sentences += if (clauses.isEmpty()) "$head." else {
                head + " " + clauses.joinToString(app.getString(R.string.eco_clause_or)) + "."
            }
            rest = advice.tips.filter { clause(it) == null }
        } else {
            sentences += bandSentence(advice)
            rest = advice.tips
        }
        rest.mapTo(sentences, ::sentence)
        return sentences.joinToString(" ")
    }

    /**
     * CP-090. The clause for hard power at a low charge or in the cold, added to the spoken line;
     * none under [STRESS_MINUTES], and none when the trip cannot say.
     */
    fun stress(samples: List<TripSample>): String? {
        val stress = DrivingStress.of(samples) ?: return null
        if (stress.stressedMinutes < STRESS_MINUTES) return null
        return app.getString(R.string.eco_trip_stress, stress.stressedMinutes)
    }

    /** Lines for the overview under the trip diagrams, or the reason there are none. */
    fun history(advice: HistoryAdvice): List<String> {
        if (advice.reviewedTrips == 0 && !advice.hasAdvice) {
            return listOf(app.getString(R.string.eco_history_waiting))
        }
        val lines = ArrayList<String>()
        if (advice.heavierTrips > 0) {
            val amount = advice.excessKwh?.let { app.getString(R.string.eco_amount_kwh, it) }
                ?: advice.excessSocPoints?.let { app.getString(R.string.eco_amount_soc, it) }
            lines += if (amount != null) {
                app.getString(
                    R.string.eco_history_heavier, advice.heavierTrips, advice.reviewedTrips, amount,
                )
            } else {
                app.getString(
                    R.string.eco_history_heavier_count, advice.heavierTrips, advice.reviewedTrips,
                )
            }
        } else if (advice.reviewedTrips > 0) {
            lines += app.getString(R.string.eco_history_on_average, advice.reviewedTrips)
        }
        advice.style?.let {
            lines += app.getString(R.string.eco_history_style, it.harshMinutes, it.sharePercent)
        }
        val motorway = advice.motorwaySaving
        if (motorway != null) {
            lines += app.getString(
                if (advice.motorwayBasis == SpeedWhatIfBasis.ENERGY_KWH) {
                    R.string.eco_history_motorway_kwh
                } else R.string.eco_history_motorway_soc,
                motorway,
                advice.motorwayTrips,
            )
        }
        advice.cabinMeanPercent?.let {
            lines += app.getString(R.string.eco_history_cabin, it.roundToInt())
        }
        advice.windowsMeanPercent?.let {
            lines += app.getString(R.string.eco_history_windows, it.roundToInt())
        }
        return lines
    }

    private fun bandSentence(advice: TripAdvice): String {
        val band = advice.band ?: return app.getString(R.string.eco_trip_no_verdict)
        val delta = advice.deltaPercent?.let { abs(it).roundToInt() }
        return when {
            band == EcoBand.BETTER && delta != null ->
                app.getString(R.string.eco_trip_better, delta)
            band == EcoBand.WORSE && delta != null ->
                app.getString(R.string.eco_trip_worse, delta)
            else -> app.getString(R.string.eco_trip_typical)
        }
    }

    /** The tips that finish "you could have saved … by …"; the others stand alone. */
    private fun clause(tip: EcoTip): String? = when (tip) {
        is EcoTip.Smoother -> app.getString(R.string.eco_clause_smoother, tip.harshMinutes)
        is EcoTip.EcoMode -> app.getString(R.string.eco_clause_eco_mode)
        is EcoTip.SlowerMotorway ->
            app.getString(R.string.eco_clause_motorway, tip.finding.referenceSpeedKmh)
        is EcoTip.SnowMode, is EcoTip.Cabin, is EcoTip.Windows -> null
    }

    private fun sentence(tip: EcoTip): String = when (tip) {
        is EcoTip.Smoother ->
            app.getString(R.string.eco_tip_smoother, tip.harshMinutes, tip.sharePercent)
        is EcoTip.EcoMode -> app.getString(
            if (tip.current == null) R.string.eco_tip_eco_mode_unknown else R.string.eco_tip_eco_mode,
            tip.current?.let { app.getString(it.labelRes) }.orEmpty(),
        )
        is EcoTip.SnowMode -> app.getString(R.string.eco_tip_snow_mode, tip.outsideTempCelsius)
        is EcoTip.SlowerMotorway -> app.getString(
            if (tip.finding.basis == SpeedWhatIfBasis.ENERGY_KWH) {
                R.string.trip_eco_finding_motorway_energy
            } else R.string.trip_eco_finding_motorway_soc,
            tip.finding.referenceSpeedKmh,
            tip.finding.motorwayDistanceKm,
            tip.finding.savingLow,
            tip.finding.savingHigh,
        )
        is EcoTip.Cabin ->
            app.getString(R.string.trip_eco_finding_cabin, tip.finding.kwh, tip.finding.sharePercent)
        is EcoTip.Windows -> app.getString(
            R.string.trip_eco_finding_windows,
            tip.finding.kwh,
            tip.finding.sharePercent,
            tip.finding.openDistanceKm,
        )
    }

    private companion object {
        /** Under this, a hard start or two: not worth a sentence. */
        const val STRESS_MINUTES = 2.0
    }
}
