package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAverageEvidenceTest {

    @Test
    fun `comparison keeps gross net and vehicle values distinct`() {
        val summary = EnergyTripSummary(
            startedAtMs = 0L,
            endedAtMs = 1_000L,
            durationMs = 1_000L,
            distanceKm = 17.605,
            startSocPercent = 67f,
            endSocPercent = 62.7f,
            consumedKwh = 2.873,
            regeneratedKwh = 0.598,
            distanceAvailable = true,
        )

        val line = TripAverageEvidence.describe(summary, vehicleRaw = 13.1f)

        assertTrue(line.contains("app_gross_kwh_per_100km=16.319"))
        assertTrue(line.contains("app_net_kwh_per_100km=12.922"))
        assertTrue(line.contains("vehicle_consumption_per_km_raw=13.100"))
    }
}
