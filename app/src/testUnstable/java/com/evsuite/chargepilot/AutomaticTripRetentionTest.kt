package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticTripRetentionTest {

    @Test
    fun `unstable stores only automatic trips longer than five kilometres`() {
        assertFalse(AutomaticTripRetention.shouldStore(true, trip(5.0)))
        assertTrue(AutomaticTripRetention.shouldStore(true, trip(5.001)))
        assertFalse(
            AutomaticTripRetention.shouldStore(true, trip(10.0, distanceAvailable = false))
        )
        assertTrue(AutomaticTripRetention.shouldStore(false, trip(1.0)))
    }

    private fun trip(
        distanceKm: Double,
        distanceAvailable: Boolean = true,
    ) = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = 600_000L,
        durationMs = 600_000L,
        distanceKm = distanceKm,
        startSocPercent = 80f,
        endSocPercent = 78f,
        consumedKwh = 1.0,
        regeneratedKwh = 0.1,
        distanceAvailable = distanceAvailable,
    )
}
