package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripOverviewTest {
    private fun summary(
        startedAtMs: Long,
        km: Double = 10.0,
        consumed: Double? = 2.0,
        regenerated: Double? = 0.5,
        startSoc: Float? = 80f,
        endSoc: Float? = 78f,
    ) = EnergyTripSummary(
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 600_000L,
        durationMs = 600_000L,
        distanceKm = km,
        startSocPercent = startSoc,
        endSocPercent = endSoc,
        consumedKwh = consumed,
        regeneratedKwh = regenerated,
        distanceAvailable = true,
    )

    private fun sample(atMs: Long, speed: Float?, power: Float?) =
        TripSample(atMs, speed, power, null, null, null, null, null, null, null)

    @Test fun `consumption is net of regeneration and ignores trips missing either half`() {
        val totals = TripOverview.totals(
            listOf(summary(1, km = 10.0), summary(2, km = 5.0, consumed = null, regenerated = null)),
        )
        assertEquals(2, totals.trips)
        assertEquals(15.0, totals.distanceKm!!, 1e-9)
        assertEquals(1.5, totals.netKwh!!, 1e-9)
        assertEquals(15.0, totals.consumptionKwhPer100Km!!, 1e-9)
    }

    @Test fun `a track becomes segments, a gap past the bound does not`() {
        // 36 km/h for 5 s is 50 m; 7.2 kW for 5 s is 0.01 kWh.
        val trip = StoredTrip(
            summary(1),
            listOf(sample(0, 36f, 7.2f), sample(5_000, 36f, 7.2f), sample(60_000, 36f, 7.2f)),
        )
        val segments = TripOverview.segments(trip)
        assertEquals(1, segments.size)
        assertEquals(0.05, segments[0].distanceKm, 1e-9)
        assertEquals(0.01, segments[0].netKwh, 1e-9)
        // Without a track the totals are the one segment.
        assertEquals(listOf(DriveSegment(10.0, 1.5)), TripOverview.segments(StoredTrip(summary(1))))
    }

    @Test fun `the window takes the newest kilometres and cuts the straddling trip`() {
        val newest = StoredTrip(summary(2, km = 10.0, consumed = 1.0, regenerated = 0.0))
        val older = StoredTrip(summary(1, km = 20.0, consumed = 4.0, regenerated = 0.0))
        val window = TripOverview.window(listOf(newest, older), 15.0)
        assertEquals(listOf(DriveSegment(5.0, 1.0), DriveSegment(10.0, 1.0)), window)
        assertEquals(2.0 * 100.0 / 15.0, TripOverview.consumption(window)!!, 1e-9)
    }

    @Test fun `the trace splits by distance and leaves undriven stretches empty`() {
        val trace = TripOverview.trace(listOf(DriveSegment(2.0, 0.2), DriveSegment(2.0, 0.6)), 4)
        assertEquals(listOf(10f, 10f, 30f, 30f), trace)
        assertEquals(emptyList<Float?>(), TripOverview.trace(emptyList(), 4))
    }

    @Test fun `since the charge stops at the first rise and refuses an unreadable gap`() {
        val newest = summary(3, startSoc = 90f, endSoc = 85f)
        val middle = summary(2, startSoc = 60f, endSoc = 55f)
        val oldest = summary(1, startSoc = 70f, endSoc = 60f)
        assertEquals(
            listOf(newest),
            TripOverview.sinceCharge(85f, null, listOf(newest, middle, oldest)),
        )
        // Charged since the last trip: nothing driven since.
        assertEquals(emptyList<EnergyTripSummary>(), TripOverview.sinceCharge(95f, null, listOf(newest)))
        // A missing reading before any rise cannot be told from "no charge".
        assertNull(TripOverview.sinceCharge(85f, null, listOf(newest, summary(2, endSoc = null))))
    }
}
