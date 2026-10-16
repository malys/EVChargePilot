package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoProgressTest {

    private val day = 24 * 3_600_000L
    private val now = 30 * day

    /** 10 km, 20 min moving, no hard time; a null [brakings] is a trip recorded before counting. */
    private fun trip(startedAtMs: Long, brakings: Int?) = StoredTrip(
        EnergyTripSummary(
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 1_200_000L,
            durationMs = 1_200_000L,
            distanceKm = 10.0,
            startSocPercent = 80f,
            endSocPercent = 78f,
            consumedKwh = null,
            regeneratedKwh = null,
            distanceAvailable = true,
            movingMs = brakings?.let { 1_200_000L },
            harshMs = brakings?.let { 0L },
            harshAccelerations = 0,
            harshBrakings = brakings,
        )
    )

    private fun progress(vararg brakingsNewestFirst: Int?) = EcoProgress.of(
        brakingsNewestFirst.mapIndexed { index, b -> trip(now - (index + 1) * day, b) },
        emptyMap(),
        now,
    )

    @Test fun `a trip better than every earlier one is a record and extends the run`() {
        // Scores newest first: 100, then 71 three times (anticipation 50, smoothness 100).
        val result = progress(0, 3, 3, 3)
        assertEquals(100, result.score!!.total)
        assertTrue(result.isRecord)
        assertEquals(1, result.streak)
        assertEquals(71, result.averageBefore)
    }

    @Test fun `a run stops at the first trip below its own average`() {
        val result = progress(1, 1, 6, 3, 3, 3)
        assertFalse(result.isRecord)
        assertEquals(2, result.streak)
    }

    @Test fun `an unscored trip breaks the run and claims no record`() {
        val result = progress(null, 0, 3, 3, 3)
        assertNull(result.score)
        assertFalse(result.isRecord)
        assertEquals(0, result.streak)
    }

    @Test fun `the weekly target is a fifth fewer hard stops than last week`() {
        // Last week: 2 trips of 10 km with 5 stops each, 5 per 10 km. This week: 2 per 10 km.
        val trips = listOf(
            trip(now - 1 * day, 2),
            trip(now - 2 * day, 2),
            trip(now - 8 * day, 5),
            trip(now - 9 * day, 5),
        )
        val challenge = EcoProgress.of(trips, emptyMap(), now).challenge!!
        assertEquals(4.0, challenge.targetPer10Km, 1e-9)
        assertEquals(5.0, challenge.lastWeekPer10Km, 1e-9)
        assertEquals(2.0, challenge.thisWeekPer10Km!!, 1e-9)
    }
}
