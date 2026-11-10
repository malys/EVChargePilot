package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcoClimateTest {

    private fun sample(on: Boolean?) = TripSample(
        atMs = 0L,
        speedKmh = 50f,
        batteryPowerKw = null,
        socPercent = null,
        outsideTempCelsius = 5f,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = on,
        climateAcOn = null,
        climateFanLevel = null,
    )

    private fun trip(vararg states: Boolean?) = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = 0L,
            endedAtMs = 1L,
            durationMs = 1L,
            distanceKm = 1.0,
            startSocPercent = null,
            endSocPercent = null,
            consumedKwh = null,
            regeneratedKwh = null,
            distanceAvailable = true,
        ),
        samples = states.map(::sample),
    )

    @Test
    fun `a drive is on, off, or both by the share of its track`() {
        assertEquals(true, EcoTripReviewer.climateOn(trip(true, true, false)))
        assertEquals(false, EcoTripReviewer.climateOn(trip(false, false, false)))
        assertNull(EcoTripReviewer.climateOn(trip(true, false, false)))
        assertNull(EcoTripReviewer.climateOn(trip(null, null)))
    }

    @Test
    fun `a clear gap between the two sides is the climate's finding`() {
        val finding = EcoTripReviewer.climate(listOf(20.0, 22.0, 24.0), listOf(0.0, 2.0, -2.0))
        assertEquals(22.0, finding!!.extraPercent, 1e-9)
        assertEquals(3, finding.withTrips)
        assertEquals(3, finding.withoutTrips)
    }

    @Test
    fun `a gap inside the noise, a cheaper climate or too few drives say nothing`() {
        assertNull(EcoTripReviewer.climate(listOf(-10.0, 15.0, 5.0), listOf(0.0, 2.0, -2.0)))
        assertNull(EcoTripReviewer.climate(listOf(-20.0, -22.0, -24.0), listOf(0.0, 2.0, -2.0)))
        assertNull(EcoTripReviewer.climate(listOf(20.0, 22.0), listOf(0.0, 2.0, -2.0)))
    }
}
