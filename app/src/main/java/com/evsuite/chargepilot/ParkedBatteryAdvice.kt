package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryLedgerEntry
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/**
 * What the battery ledger says about how the car is left parked (CP-088). Pure: the ledger in,
 * findings out; [BatteryAdvisor] decides which are new and says them.
 *
 * Nothing here writes to the car. The charge limit is read and a better one proposed; changing
 * it stays with the driver.
 */
internal object ParkedBatteryAdvice {

    /**
     * The smallest limit that covers the driver's own days.
     *
     * Distance is the car's odometer between ledger entries, per local day, over the driving days
     * of the last [WINDOW_DAYS]. Charge per km is the driver's own, measured over the same
     * entries: charge points lost where the odometer moved. No kWh, no capacity, no fit to trust.
     */
    data class LimitProposal(
        val proposedPercent: Int,
        /** The 95th-percentile day in charge points, reserve included, before rounding. */
        val neededPercent: Double,
        /** Share of the driving days the proposed limit covers, 0..1. */
        val coveredShare: Double,
        val drivingDays: Int,
    )

    enum class LimitVerdict { LOWER, RAISE, UNREAD }

    data class Dwell(val startMs: Long, val endMs: Long, val socPercent: Double) {
        val hours: Double get() = (endMs - startMs) / HOUR_MS
    }

    data class Heat(val day: String, val socPercent: Double, val outsideCelsius: Double)

    fun limitProposal(entries: List<BatteryLedgerEntry>, nowMs: Long, zone: ZoneId): LimitProposal? {
        val since = nowMs - WINDOW_DAYS * DAY_MS
        val kmByDay = HashMap<String, Double>()
        var km = 0.0
        var points = 0.0
        entries.zipWithNext().forEach { (a, b) ->
            if (b.atMs < since) return@forEach
            val from = a.odometerKm ?: return@forEach
            val to = b.odometerKm ?: return@forEach
            val moved = (to - from).toDouble()
            if (moved <= 0.0 || moved > MAX_STEP_KM) return@forEach
            val day = day(b.atMs, zone)
            kmByDay[day] = (kmByDay[day] ?: 0.0) + moved
            // A step that also charged says nothing about consumption.
            if (b.socPercent <= a.socPercent) {
                km += moved
                points += a.socPercent - b.socPercent
            }
        }
        if (kmByDay.size < MIN_DRIVING_DAYS || km < MIN_CONSUMPTION_KM || points <= 0.0) return null
        val perKm = points / km
        val needs = kmByDay.values.map { it * perKm + RESERVE_PERCENT }.sorted()
        val needed = needs[ceil(PERCENTILE * needs.size).toInt() - 1]
        val proposed = (ceil(needed / LIMIT_STEP).toInt() * LIMIT_STEP).coerceIn(LIMIT_FLOOR, 100)
        return LimitProposal(
            proposedPercent = proposed,
            neededPercent = needed,
            coveredShare = needs.count { it <= proposed }.toDouble() / needs.size,
            drivingDays = needs.size,
        )
    }

    fun limitVerdict(proposal: LimitProposal, limitPercent: Int?): LimitVerdict? {
        val limit = limitPercent?.takeIf { it in 1..100 } ?: return LimitVerdict.UNREAD
        return when {
            limit >= proposal.proposedPercent + LOWER_MARGIN -> LimitVerdict.LOWER
            limit < proposal.neededPercent -> LimitVerdict.RAISE
            else -> null
        }
    }

    /** Runs of entries at [DWELL_SOC_PERCENT] or more with the odometer still, [DWELL_HOURS] or longer. */
    fun dwells(entries: List<BatteryLedgerEntry>): List<Dwell> {
        val found = ArrayList<Dwell>()
        var run = ArrayList<BatteryLedgerEntry>()
        fun close() {
            if (run.size >= 2 && run.last().atMs - run.first().atMs >= DWELL_HOURS * HOUR_MS) {
                found += Dwell(run.first().atMs, run.last().atMs, run.minOf { it.socPercent }.toDouble())
            }
            run = ArrayList()
        }
        entries.forEach { entry ->
            val still = run.isEmpty() || sameOdometer(run.first(), entry)
            if (entry.socPercent < DWELL_SOC_PERCENT || !still) close()
            if (entry.socPercent >= DWELL_SOC_PERCENT) run += entry
        }
        close()
        return found
    }

    /**
     * Days the car was seen parked at [HEAT_SOC_PERCENT] or more with [HEAT_OUTSIDE_CELSIUS] or
     * more outside. Only what the head unit saw awake: an asleep car in the sun leaves no entry.
     */
    fun heat(entries: List<BatteryLedgerEntry>, zone: ZoneId): List<Heat> = entries
        .filterIndexed { i, entry ->
            val parked = entry.parked ?: (i > 0 && sameOdometer(entries[i - 1], entry))
            parked && entry.socPercent >= HEAT_SOC_PERCENT &&
                (entry.outsideTempCelsius ?: return@filterIndexed false) >= HEAT_OUTSIDE_CELSIUS
        }
        .groupBy { day(it.atMs, zone) }
        .map { (day, list) ->
            Heat(day, list.maxOf { it.socPercent }.toDouble(), list.maxOf { it.outsideTempCelsius!! }.toDouble())
        }
        .sortedBy { it.day }

    private fun sameOdometer(a: BatteryLedgerEntry, b: BatteryLedgerEntry): Boolean {
        val from = a.odometerKm ?: return true
        val to = b.odometerKm ?: return true
        return to - from < STILL_KM
    }

    fun day(atMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toString()

    const val WINDOW_DAYS = 60
    const val PERCENTILE = 0.95
    const val RESERVE_PERCENT = 15.0
    const val LIMIT_STEP = 5
    const val LIMIT_FLOOR = 60
    const val LOWER_MARGIN = 10
    /** Fewer driving days than two weeks is not a pattern. */
    const val MIN_DRIVING_DAYS = 14
    const val MIN_CONSUMPTION_KM = 100.0
    /** A ledger step longer than this is a gap in the record, not a day's driving. */
    const val MAX_STEP_KM = 1_000.0
    const val STILL_KM = 0.5f
    const val DWELL_SOC_PERCENT = 90f
    const val DWELL_HOURS = 12
    const val HEAT_SOC_PERCENT = 80f
    const val HEAT_OUTSIDE_CELSIUS = 30f
    private const val HOUR_MS = 3_600_000.0
    private const val DAY_MS = 86_400_000L
}
