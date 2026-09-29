package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.ChargeCurveCheck
import com.evsuite.hardware.telemetry.ChargeDuration
import com.evsuite.hardware.telemetry.ChargeDurationRefusal
import com.evsuite.hardware.telemetry.ChargeDurationResult
import com.evsuite.hardware.telemetry.ChargeEnergy
import java.util.Date
import java.util.TimeZone
import kotlin.math.floor

/**
 * The charge in progress, followed from the samples (CP-089).
 *
 * The app reads no charging state it can trust, so a charge is what the ledger calls one: the
 * charge rising while the car stands still. It opens one point above the lowest charge seen
 * since the car stopped, and closes when the car moves or the charge has not risen for
 * [STALL_MS]. An unreadable speed opens nothing and closes nothing.
 *
 * The duration is computed when the charge opens and again each time it enters a new 10-point
 * band, never every sample. A band the charge crossed whole, edge to edge, is checked against the
 * same band in the driver's past charges.
 *
 * Pure apart from its two sources; runs on the sampler thread.
 */
internal class ChargeCompanion(
    /** The driver's own past charges, read when a charge opens. */
    private val history: () -> List<ChargeEnergy>,
    /** The car's charge limit, read when a charge opens; 100 when unreadable. */
    private val limit: () -> Int?,
) {
    data class Live(
        val startedAtMs: Long,
        val startSocPercent: Double,
        val targetPercent: Int,
        /** When [result] was computed: its minutes count from here. */
        val computedAtMs: Long,
        val result: ChargeDurationResult,
    )

    sealed interface Event {
        data class Started(val live: Live) : Event
        data class Slower(val check: ChargeCurveCheck) : Event
        data class Ended(
            val pointsAdded: Double,
            val durationMs: Long,
            val checkedBands: Int,
            val slowerBands: Int,
        ) : Event
    }

    var live: Live? = null
        private set
    private var charges: List<ChargeEnergy> = emptyList()
    private var lowSoc: Double? = null
    private var lowAtMs = 0L
    private var topSoc = 0.0
    private var lastRiseAtMs = 0L
    /** Null while in the band the charge opened in: that band was not crossed whole. */
    private var bandEnteredAtMs: Long? = null
    private var bandEnteredSoc = 0.0
    private var checked = 0
    private var slower = 0

    fun observe(atMs: Long, socPercent: Double?, speedKmh: Float?, outsideCelsius: Double?): List<Event> {
        val known = speedKmh != null && speedKmh.isFinite() && speedKmh >= 0f
        val moving = known && speedKmh!! > STILL_KMH
        val events = ArrayList<Event>()
        if (live != null && (moving || atMs - lastRiseAtMs > STALL_MS)) end()?.let { events += it }
        if (moving) lowSoc = null
        if (!known || moving || socPercent == null) return events
        val open = live
        if (open == null) {
            val low = lowSoc
            if (low == null || socPercent <= low) {
                lowSoc = socPercent
                lowAtMs = atMs
            } else if (socPercent - low >= RISE_PERCENT) {
                events += start(atMs, low, socPercent, outsideCelsius)
            }
            return events
        }
        if (socPercent <= topSoc) return events
        lastRiseAtMs = atMs
        val from = band(topSoc)
        val to = band(socPercent)
        if (to > from) {
            val enteredAt = bandEnteredAtMs
            if (enteredAt != null && to == from + 1) {
                val minutesPerPoint = (atMs - enteredAt) / MINUTE_MS / (socPercent - bandEnteredSoc)
                ChargeDuration.curveCheck(charges, from * ChargeDuration.BAND_PERCENT, minutesPerPoint)?.let {
                    checked++
                    if (it.slower) {
                        slower++
                        events += Event.Slower(it)
                    }
                }
            }
            bandEnteredAtMs = atMs
            bandEnteredSoc = socPercent
            live = open.copy(
                computedAtMs = atMs,
                result = ChargeDuration.estimate(charges, socPercent, open.targetPercent, outsideCelsius),
            )
        }
        topSoc = socPercent
        return events
    }

    private fun start(atMs: Long, low: Double, socPercent: Double, outsideCelsius: Double?): Event {
        // The charge in progress may already be in the ledger's analysis; it is not its own past.
        charges = history().filter { it.session.endedAtMs < lowAtMs }
        val target = limit()?.takeIf { it in 1..100 } ?: 100
        topSoc = socPercent
        lastRiseAtMs = atMs
        bandEnteredAtMs = null
        checked = 0
        slower = 0
        val opened = Live(
            startedAtMs = lowAtMs,
            startSocPercent = low,
            targetPercent = target,
            computedAtMs = atMs,
            result = ChargeDuration.estimate(charges, socPercent, target, outsideCelsius),
        )
        live = opened
        return Event.Started(opened)
    }

    private fun end(): Event? {
        val open = live ?: return null
        live = null
        lowSoc = topSoc
        lowAtMs = lastRiseAtMs
        return Event.Ended(topSoc - open.startSocPercent, lastRiseAtMs - open.startedAtMs, checked, slower)
    }

    private fun band(socPercent: Double): Int = floor(socPercent / ChargeDuration.BAND_PERCENT).toInt()

    companion object {
        /** What the Battery page shows; written by the sampler thread only. */
        @Volatile
        var latest: Live? = null

        const val RISE_PERCENT = 1.0
        /** A slow charger's taper takes a quarter of an hour a point; this is three of those. */
        const val STALL_MS = 45 * 60_000L
        private const val STILL_KMH = 0.1f
        private const val MINUTE_MS = 60_000.0
    }
}

/** The companion's words, in the app's language (CP-077). */
internal class ChargeCompanionText(private val res: Context, private val zone: TimeZone = TimeZone.getDefault()) {

    fun live(live: ChargeCompanion.Live): String = when (val result = live.result) {
        is ChargeDurationResult.Ready -> {
            val e = result.estimate
            val at = { minutes: Double -> clock(live.computedAtMs + (minutes * MINUTE_MS).toLong()) }
            val stretch = e.lastStretchMinutes
            if (stretch != null) {
                res.getString(
                    R.string.charge_live_via_eighty,
                    at(e.minutes - stretch), live.targetPercent, at(e.minutes), at(e.lowMinutes), at(e.highMinutes),
                )
            } else {
                res.getString(
                    R.string.charge_live_until, live.targetPercent, at(e.minutes), at(e.lowMinutes), at(e.highMinutes),
                )
            }
        }
        is ChargeDurationResult.Refused -> when (result.reason) {
            ChargeDurationRefusal.AT_TARGET -> res.getString(R.string.charge_live_at_target, live.targetPercent)
            ChargeDurationRefusal.NO_WATCHED_CHARGE -> res.getString(R.string.charge_live_no_history)
            ChargeDurationRefusal.MISSING_BAND -> {
                val from = result.bandFromPercent ?: 0
                res.getString(R.string.charge_live_missing_band, from, from + ChargeDuration.BAND_PERCENT)
            }
        }
    }

    /** The journal's line for a charge that opened with an end time; none without one. */
    fun started(live: ChargeCompanion.Live): String? {
        val e = (live.result as? ChargeDurationResult.Ready)?.estimate ?: return null
        val stretch = e.lastStretchMinutes
        val rest = e.minutes - (stretch ?: 0.0)
        if (stretch == null || stretch < rest) return live(live)
        return live(live) + " " + res.getString(
            R.string.advice_charge_last_stretch, live.targetPercent, stretch / 60.0, rest / 60.0,
        )
    }

    fun slower(check: ChargeCurveCheck): String = res.getString(
        R.string.advice_charge_slower,
        check.bandFromPercent,
        check.bandFromPercent + ChargeDuration.BAND_PERCENT,
        check.minutesPerPoint,
        check.usualLowMinutesPerPoint,
        check.usualHighMinutesPerPoint,
        check.pastCharges,
    )

    fun ended(ended: ChargeCompanion.Event.Ended): String = res.getString(
        R.string.advice_charge_end,
        ended.pointsAdded,
        ended.durationMs / 3_600_000.0,
        res.getString(
            when {
                ended.checkedBands == 0 -> R.string.advice_charge_curve_unchecked
                ended.slowerBands == 0 -> R.string.advice_charge_curve_usual
                else -> R.string.advice_charge_curve_slower
            },
        ),
    )

    private fun clock(atMs: Long): String = android.text.format.DateFormat.getTimeFormat(res)
        .apply { timeZone = zone }
        .format(Date(atMs))

    private companion object {
        const val MINUTE_MS = 60_000.0
    }
}
