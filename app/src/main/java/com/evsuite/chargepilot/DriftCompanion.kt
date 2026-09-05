package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.chargepilot.route.RouteWhatIf
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.NavGuidance
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.PlanDrift
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import java.io.File
import java.util.Locale

/**
 * The line that notices the chosen plan stopped being the trip, and what would restore it.
 *
 * **It costs nothing to run.** Charge comes from the sampler the dashboard already reads, the
 * distance from an odometer getter, the remaining distance from a navigation getter that answers
 * synchronously — no request, no quota, no network. CP-058 is emphatic about this and the shape
 * of this file is the proof: nothing here can reach a transport, because nothing here imports
 * one. The re-proposal that *does* cost a request stays on the charge-stop screen, behind a tap,
 * parked.
 *
 * **One line, stating a consequence.** No dialog, no sound, no countdown, nothing that wants a
 * decision at 130 km/h. A charge on arrival and a speed that gives it back are facts about the
 * road; "slow down" is an instruction, and this app does not give them.
 *
 * **Recomputed once a minute.** The dashboard renders at 1 Hz and drift does not move at 1 Hz. A
 * figure that changes on every glance is a figure nobody trusts, and the sixty seconds also keep
 * two binder reads off the sampling tick.
 */
class DriftCompanion(context: Context) {

    private val app = context.applicationContext

    /** Fitted off the main thread, and only when there is a plan to be fitted for. */
    @Volatile
    private var model: SocConsumptionModel? = null

    @Volatile
    private var modelLoaded = false

    private var lastComputedAtMs = 0L
    private var lastLine: String? = null

    /** Reads the trip history and fits CP-052's model. Call on a worker thread. */
    fun load() {
        if (FollowedPlan.read(app) == null) {
            modelLoaded = true
            return
        }
        val trips = runCatching {
            EnergyTripHistoryStore(File(app.filesDir, HISTORY_FILE)).read()
        }.getOrDefault(emptyList())
        val fit = SocConsumptionFitter().fit(trips, FirmwareInfo.getGeneration())
        model = (fit as? SocConsumptionFitResult.Ready)?.model
        modelLoaded = true
    }

    /** Forgets the plan, so the line stops. */
    fun forget() {
        FollowedPlan.clear(app)
        lastComputedAtMs = 0L
        lastLine = null
    }

    /**
     * The line to show, or null when there is nothing worth a line.
     *
     * @param nowMs passed in rather than read so a test can drive the once-a-minute gate.
     */
    fun line(snapshot: EnergySnapshot?, nowMs: Long = System.currentTimeMillis()): String? {
        if (nowMs - lastComputedAtMs < RECOMPUTE_MS) return lastLine
        lastComputedAtMs = nowMs
        lastLine = compute(snapshot, nowMs)
        return lastLine
    }

    private fun compute(snapshot: EnergySnapshot?, nowMs: Long): String? {
        val followed = FollowedPlan.read(app, nowMs) ?: return null
        val drivenKm = drivenKm(followed, snapshot)
        val remainingKm = remainingKm(followed, drivenKm)
        val verdict = PlanDrift.check(
            followed.drift,
            drivenKm,
            snapshot?.socPercent?.toDouble(),
            remainingKm = remainingKm,
        )
        // Distances and charges only. This file leaves the car on a USB stick, and where the
        // driver was going is not in the store this reads, let alone in this line.
        ValidationProbe.record(ValidationQuestion.PLAN_DRIFT) {
            "leg=${format(followed.drift.legKm, "%.0f")} km " +
                "driven=${drivenKm?.let { format(it, "%.0f") } ?: "?"} km " +
                "guidance=${remainingKm?.let { format(it, "%.0f") } ?: "none"} km " +
                "soc=${snapshot?.socPercent?.toInt() ?: "?"} % -> " +
                when (verdict) {
                    is PlanDrift.Verdict.Unavailable -> "unavailable(${verdict.reason})"
                    is PlanDrift.Verdict.Holding ->
                        "holding arrival=${format(verdict.reading.arrivalPercent, "%.0f")} %"
                    is PlanDrift.Verdict.Short ->
                        "short arrival=${format(verdict.reading.arrivalPercent, "%.0f")} % " +
                            "by ${format(verdict.shortfallPercent, "%.0f")} %"
                }
        }
        return when (verdict) {
            is PlanDrift.Verdict.Unavailable -> when (verdict.reason) {
                // The leg is behind the car, or there is nothing to follow. Both mean the
                // companion has finished, and a finished companion says nothing at all.
                PlanDrift.Reason.ARRIVED, PlanDrift.Reason.NO_PLAN -> null
                PlanDrift.Reason.TOO_SOON -> app.getString(
                    R.string.drift_too_soon,
                    format(PlanDrift.MIN_DISTANCE_KM, "%.0f"),
                )
                PlanDrift.Reason.NO_DISTANCE -> app.getString(R.string.drift_no_distance)
                PlanDrift.Reason.NO_CHARGE -> app.getString(R.string.drift_no_charge)
                PlanDrift.Reason.CHARGED_EN_ROUTE -> app.getString(R.string.drift_charged)
            }

            is PlanDrift.Verdict.Holding -> app.getString(
                R.string.drift_holding,
                format(verdict.reading.arrivalPercent, "%.0f"),
                format(verdict.reading.bandPercent, "%.0f"),
                format(verdict.reading.remainingKm, "%.0f"),
            )

            is PlanDrift.Verdict.Short -> shortLine(followed, verdict, snapshot, drivenKm)
        }
    }

    /**
     * The consequence, and the mildest speed that undoes it.
     *
     * The speed is CP-049's calculation on the road that is left, with the shortfall as its
     * predicate: the question asked of it is not "what would slowing down save" but "which is the
     * fastest speed that saves *this much*". Nothing is fetched to answer it — the sections were
     * written down when the plan was chosen, and the model was fitted from trips already on disk.
     */
    private fun shortLine(
        followed: FollowedPlan.Values,
        verdict: PlanDrift.Verdict.Short,
        snapshot: EnergySnapshot?,
        drivenKm: Double?,
    ): String {
        val arrival = format(verdict.reading.arrivalPercent, "%.0f")
        val shortfall = format(verdict.shortfallPercent, "%.0f")
        val restore = restore(followed, verdict, snapshot, drivenKm)
        return when (restore) {
            is Restore.Speed -> app.getString(
                R.string.drift_short_restore,
                arrival,
                shortfall,
                restore.option.speedKmh,
                format(restore.option.affectedKm, "%.0f"),
                format(restore.option.delayMinutes, "%.0f"),
            )

            is Restore.None -> app.getString(
                R.string.drift_short_none, arrival, shortfall, app.getString(restore.reason)
            )
        }
    }

    private sealed interface Restore {
        data class Speed(val option: RouteWhatIf.Slower) : Restore

        data class None(val reason: Int) : Restore
    }

    private fun restore(
        followed: FollowedPlan.Values,
        verdict: PlanDrift.Verdict.Short,
        snapshot: EnergySnapshot?,
        drivenKm: Double?,
    ): Restore {
        if (followed.sections.isEmpty()) {
            return Restore.None(R.string.drift_restore_no_route)
        }
        if (!modelLoaded) return Restore.None(R.string.drift_restore_not_loaded)
        val ahead = FollowedPlan.ahead(followed.sections, drivenKm ?: 0.0)
        val result = RouteWhatIf.slower(
            ahead,
            model,
            snapshot?.outsideTempCelsius?.toDouble(),
            // The saving has to cover the shortfall at its pessimistic edge, which is the same
            // edge the shortfall itself was measured at. A speed that only just covers the
            // optimistic one is a speed that does not.
            removesStop = { saved -> saved >= verdict.shortfallPercent },
        )
        return when (result) {
            is RouteWhatIf.Result.Unavailable -> Restore.None(
                when (result.reason) {
                    SpeedWhatIfUnavailable.MODEL_NOT_TRAINED -> R.string.drift_restore_no_model
                    SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION -> R.string.drift_restore_no_road
                    SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE ->
                        R.string.drift_restore_envelope
                    else -> R.string.drift_restore_no_temperature
                }
            )

            is RouteWhatIf.Result.Ready -> result.headline?.let { Restore.Speed(it) }
                ?: Restore.None(R.string.drift_restore_not_enough)
        }
    }

    /**
     * Road covered since the plan was chosen.
     *
     * The adapter's odometer, because it is what reads on this car: `PERF_ODOMETER` answered
     * nothing on SWI68 (CP-003) and the trip recorder measures a trip, which may have started
     * before the plan or not at all. Whole kilometres is coarse, and [PlanDrift] carries that
     * coarseness in its band rather than pretending it away.
     */
    private fun drivenKm(followed: FollowedPlan.Values, snapshot: EnergySnapshot?): Double? {
        SaicNav.connect(app)
        val now = runCatching { SaicNav.totalMileageKm() }.getOrNull()?.toDouble()
            ?: snapshot?.odometerKm?.toDouble()
            ?: return null
        return (now - followed.odometerAtDepartureKm).takeIf { it >= 0.0 }
    }

    /**
     * What the head unit says is left, when it is guiding, and the leg's own arithmetic otherwise.
     *
     * The car's number knows about the road actually taken and this app's does not, which matters
     * exactly when it matters most — a driver who left the planned road. It is only trusted while
     * something is being guided to, and only while it is plausible for this leg: a guidance to
     * somewhere else entirely would otherwise silently replace the plan's own distance.
     */
    private fun remainingKm(followed: FollowedPlan.Values, drivenKm: Double?): Double? {
        SaicNavGuidance.connect(app)
        val guidance: NavGuidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
            ?: return null
        val remaining = guidance.remainingDistanceKm(FirmwareInfo.getGeneration())
            ?: return null
        if (remaining <= 0.0) return null
        val expected = followed.drift.legKm - (drivenKm ?: 0.0)
        if (expected <= 0.0) return null
        return remaining.takeIf { it <= expected * MAX_GUIDANCE_RATIO }
    }

    private fun format(value: Double, pattern: String) =
        String.format(Locale.getDefault(), pattern, value)

    private companion object {
        const val HISTORY_FILE = "trips.json"
        const val RECOMPUTE_MS = 60_000L

        /**
         * How far past the plan's own remaining distance the car's number may be before it is
         * treated as being about a different journey. A detour is longer than the plan; a
         * guidance to the other side of France is not a detour.
         */
        const val MAX_GUIDANCE_RATIO = 1.5
    }
}
