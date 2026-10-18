package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.BatteryChargeSession
import com.evsuite.hardware.telemetry.ChargeCounterBehaviour
import com.evsuite.hardware.telemetry.ChargeEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeEnergyEstimateTest {

    private val pack = BatteryCapacityConfig(61.7, 100.0)
    private val hour = 3_600_000L

    /** The night of 2026-10-15: 33.8 → 80 % over 7.39 h, the head unit asleep most of it. */
    private fun charge(watched: Boolean, packDelta: Double? = null, power: Double? = null) = ChargeEnergy(
        session = BatteryChargeSession(0L, (7.39 * hour).toLong(), 33.8, 80.0, 0.0, 0.0, 15.0),
        watched = watched,
        maxEntryGapMs = 2_947_024L,
        packDeltaKwh = packDelta,
        meanPowerKw = power,
        counterBehaviour = ChargeCounterBehaviour.RESET,
        consumedDeltaKwh = null,
        regeneratedDeltaKwh = null,
        movedKm = 0.0,
        chargingStatuses = listOf(1),
    )

    @Test fun `an unwatched charge is estimated from the points and the declared capacity`() {
        val estimate = ChargeEnergyEstimate.of(charge(watched = false, packDelta = 0.0), pack)!!
        assertFalse(estimate.measured)
        assertEquals(61.7 * 0.462, estimate.kwh, 0.01)
        assertEquals(61.7 * 0.462 / 7.39, estimate.powerKw!!, 0.01)
    }

    @Test fun `a watched charge keeps its measured integral`() {
        val estimate = ChargeEnergyEstimate.of(charge(watched = true, packDelta = -27.1, power = 3.7), pack)!!
        assertTrue(estimate.measured)
        assertEquals(27.1, estimate.kwh, 1e-9)
        assertEquals(3.7, estimate.powerKw!!, 1e-9)
    }
}
