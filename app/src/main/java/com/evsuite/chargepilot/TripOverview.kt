package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.TripSampleTrack

/** A stretch of driving: how far, and what the pack gave for it net of regeneration. */
data class DriveSegment(val distanceKm: Double, val netKwh: Double)

/** Measured driving whose mean speed fell in `[fromKmh, toKmh)`; [toKmh] null for the top band. */
data class SpeedBand(
    val fromKmh: Int,
    val toKmh: Int?,
    val distanceKm: Double,
    val durationMs: Long,
    val netKwh: Double,
)

/**
 * What a group of trips adds up to, each figure summed over the trips that recorded it.
 *
 * The average is taken only over trips that recorded both distance and energy, so a trip whose
 * speed signal was missing cannot pull the consumption towards zero or infinity.
 */
data class TripTotals(
    val trips: Int,
    val durationMs: Long,
    val distanceKm: Double?,
    val netKwh: Double?,
    val consumptionKwhPer100Km: Double?,
)

/**
 * The trip screen's summary, after Tesla's energy app: consumption over the last kilometres,
 * the range it implies, and totals since the last charge and since a driver-chosen reset.
 *
 * Net everywhere — consumed minus regenerated — because that is what leaves the pack and so
 * what a range divides. The ledger beside it keeps showing the two halves separately.
 */
object TripOverview {
    /** Tesla's three windows: the last few minutes, the day, the week. */
    val WINDOWS_KM = listOf(15.0, 150.0, 300.0)

    /** Enough points for a trace to show a hill, few enough for a thumbnail to stay a line. */
    const val TRACE_POINTS = 60

    /** A rise smaller than this between two trips is gauge noise, not a charge. */
    const val CHARGE_RISE_PERCENT = 1.0f

    private const val MAX_GAP_MS = TripSampleTrack.DEFAULT_INTERVAL_MS * 2

    fun netKwh(summary: EnergyTripSummary): Double? {
        val consumed = summary.consumedKwh ?: return null
        return consumed - (summary.regeneratedKwh ?: return null)
    }

    fun totals(summaries: List<EnergyTripSummary>): TripTotals {
        val distances = summaries.mapNotNull { it.recordedDistanceKm }
        val energies = summaries.mapNotNull(::netKwh)
        val complete = summaries.filter { it.recordedDistanceKm != null && netKwh(it) != null }
        val completeKm = complete.sumOf { it.distanceKm }
        return TripTotals(
            trips = summaries.size,
            durationMs = summaries.sumOf { it.durationMs },
            distanceKm = distances.takeIf { it.isNotEmpty() }?.sum(),
            netKwh = energies.takeIf { it.isNotEmpty() }?.sum(),
            consumptionKwhPer100Km = if (completeKm >= 0.1) {
                complete.sumOf { checkNotNull(netKwh(it)) } * 100.0 / completeKm
            } else null,
        )
    }

    /**
     * A trip's segments in driving order: from its track where one survived, otherwise one
     * segment from its totals. Only intervals with speed and power at both ends count, and only
     * up to twice the track's interval, the same bound the eco replay uses.
     */
    fun segments(trip: StoredTrip): List<DriveSegment> {
        val fromTrack = trip.samples.orEmpty().zipWithNext().mapNotNull { (a, b) -> segment(a, b) }
        if (fromTrack.isNotEmpty()) return fromTrack
        val distance = trip.summary.recordedDistanceKm ?: return emptyList()
        val net = netKwh(trip.summary) ?: return emptyList()
        return listOf(DriveSegment(distance, net))
    }

    private fun segment(a: TripSample, b: TripSample): DriveSegment? {
        val gapMs = b.atMs - a.atMs
        if (gapMs <= 0L || gapMs > MAX_GAP_MS) return null
        val hours = gapMs / 3_600_000.0
        val speedA = a.speedKmh ?: return null
        val speedB = b.speedKmh ?: return null
        val powerA = a.batteryPowerKw ?: return null
        val powerB = b.batteryPowerKw ?: return null
        return DriveSegment((speedA + speedB) / 2.0 * hours, (powerA + powerB) / 2.0 * hours)
    }

    /** Below 1 km/h is standing still; the rest is cut where a driver reads the dial. */
    val SPEED_BAND_EDGES_KMH = listOf(0, 1, 30, 50, 70, 90, 110)

    /**
     * What each speed band cost on [trips]' tracks, measured and not modelled: the same
     * intervals as [segments], each filed under its mean speed. Bands nothing fell in are left
     * out. Traffic, climate and slope differ between bands too, so a gap between two rows is
     * what this driving cost, not what speed alone would.
     */
    fun speedBands(trips: List<StoredTrip>): List<SpeedBand> {
        val edges = SPEED_BAND_EDGES_KMH
        val km = DoubleArray(edges.size)
        val ms = LongArray(edges.size)
        val kwh = DoubleArray(edges.size)
        for (trip in trips) {
            for ((a, b) in trip.samples.orEmpty().zipWithNext()) {
                val segment = segment(a, b) ?: continue
                val gapMs = b.atMs - a.atMs
                val speed = segment.distanceKm / (gapMs / 3_600_000.0)
                val band = edges.indexOfLast { speed >= it }.coerceAtLeast(0)
                km[band] += segment.distanceKm
                ms[band] += gapMs
                kwh[band] += segment.netKwh
            }
        }
        return edges.indices.filter { ms[it] > 0L }.map {
            SpeedBand(edges[it], edges.getOrNull(it + 1), km[it], ms[it], kwh[it])
        }
    }

    /**
     * The newest [windowKm] of driving, oldest first, with the segment that straddles the edge
     * cut in proportion. Shorter than the window when the history is.
     */
    fun window(tripsNewestFirst: List<StoredTrip>, windowKm: Double): List<DriveSegment> {
        val taken = ArrayList<DriveSegment>()
        var covered = 0.0
        for (trip in tripsNewestFirst) {
            for (segment in segments(trip).asReversed()) {
                val left = windowKm - covered
                if (left <= 0.0) return taken.asReversed()
                if (segment.distanceKm <= left) {
                    taken += segment
                    covered += segment.distanceKm
                } else {
                    val share = left / segment.distanceKm
                    taken += DriveSegment(left, segment.netKwh * share)
                    covered = windowKm
                }
            }
        }
        return taken.asReversed()
    }

    /** Net consumption over the segments, null under 100 m where the ratio means nothing. */
    fun consumption(segments: List<DriveSegment>): Double? {
        val km = segments.sumOf { it.distanceKm }
        return if (km >= 0.1) segments.sumOf { it.netKwh } * 100.0 / km else null
    }

    /**
     * Consumption in [points] equal stretches of distance, oldest first; null where a stretch
     * held no distance. A segment crossing a boundary is split by distance, so one long
     * track-less trip reads as the flat line it honestly is rather than as a single spike.
     */
    fun trace(segments: List<DriveSegment>, points: Int = TRACE_POINTS): List<Float?> {
        val totalKm = segments.sumOf { it.distanceKm }
        if (totalKm <= 0.0 || points <= 0) return emptyList()
        val width = totalKm / points
        val km = DoubleArray(points)
        val kwh = DoubleArray(points)
        var at = 0.0
        for (segment in segments) {
            if (segment.distanceKm <= 0.0) {
                val bin = (at / width).toInt().coerceAtMost(points - 1)
                kwh[bin] += segment.netKwh
                continue
            }
            var left = segment.distanceKm
            while (left > 1e-9) {
                val bin = (at / width).toInt().coerceAtMost(points - 1)
                val room = if (bin == points - 1) left else ((bin + 1) * width - at).coerceAtLeast(1e-9)
                val step = minOf(left, room)
                km[bin] += step
                kwh[bin] += segment.netKwh * step / segment.distanceKm
                at += step
                left -= step
            }
        }
        return List(points) { i -> if (km[i] > 0.0) (kwh[i] * 100.0 / km[i]).toFloat() else null }
    }

    /**
     * The trips since the pack last took a charge, newest first, the current one included.
     *
     * A charge is a rise in charge between the end of one trip and the start of the next, or
     * between the newest trip and [currentSocPercent]. Null when a gap without a reading comes
     * before any rise: "since the charge" cannot be told from "since the history began".
     */
    fun sinceCharge(
        currentSocPercent: Float?,
        currentTrip: EnergyTripSummary?,
        storedNewestFirst: List<EnergyTripSummary>,
    ): List<EnergyTripSummary>? {
        val trips = listOfNotNull(currentTrip) + storedNewestFirst
        if (currentTrip == null) {
            val newestEnd = storedNewestFirst.firstOrNull()?.endSocPercent
            if (charged(newestEnd, currentSocPercent) ?: return null) return emptyList()
        }
        for (index in 0 until trips.lastIndex) {
            val rose = charged(trips[index + 1].endSocPercent, trips[index].startSocPercent)
                ?: return null
            if (rose) return trips.subList(0, index + 1)
        }
        // The whole history without a charge in it: the charge is older than what is kept.
        return null
    }

    private fun charged(before: Float?, after: Float?): Boolean? {
        if (before == null || after == null) return null
        return after - before > CHARGE_RISE_PERCENT
    }
}
