package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryChargeSession
import com.evsuite.hardware.telemetry.ChargeCounterBehaviour
import com.evsuite.hardware.telemetry.ChargeEnergy
import com.evsuite.hardware.telemetry.ChargePowerStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeCurveTest {

    private fun charge(vararg steps: ChargePowerStep) = ChargeEnergy(
        session = BatteryChargeSession(0L, 3_600_000L, 20.0, 80.0, 0.0, 0.0, 15.0),
        watched = true,
        maxEntryGapMs = 60_000L,
        packDeltaKwh = null,
        meanPowerKw = null,
        counterBehaviour = ChargeCounterBehaviour.RESET,
        consumedDeltaKwh = null,
        regeneratedDeltaKwh = null,
        movedKm = 0.0,
        chargingStatuses = listOf(1),
        steps = steps.toList(),
    )

    private fun step(from: Double, kw: Double, minutes: Long) =
        ChargePowerStep(from, from + 1.0, minutes * 60_000L, kw)

    @Test
    fun `AC and DC charges fill their own bands, time-weighted`() {
        val ac = charge(step(20.0, 6.0, 10), step(21.0, 7.0, 30))
        val dc = charge(step(20.0, 120.0, 1), step(60.0, 90.0, 1))
        val acCurve = ChargeCurve.measured(listOf(ac, dc), dc = false)
        val dcCurve = ChargeCurve.measured(listOf(ac, dc), dc = true)

        assertEquals(6.75, acCurve[4]!!, 1e-9)
        assertNull(acCurve[12])
        assertEquals(120.0, dcCurve[4]!!, 1e-9)
        assertEquals(90.0, dcCurve[12]!!, 1e-9)
    }

    @Test
    fun `the on-board charger is read off the AC steps, else the version's own`() {
        assertEquals(11.0, ChargeCurve.acKw(listOf(charge(step(30.0, 10.2, 5))), Mg4Variant.STANDARD_51), 0.0)
        assertEquals(6.6, ChargeCurve.acKw(listOf(charge(step(30.0, 6.1, 5))), Mg4Variant.LONG_RANGE_64), 0.0)
        assertEquals(6.6, ChargeCurve.acKw(emptyList(), Mg4Variant.STANDARD_51), 0.0)
    }

    @Test
    fun `the declared capacity picks the pack, a fast DC step rules out the small one`() {
        assertEquals(Mg4Variant.LONG_RANGE_64, Mg4Variant.of(61.7, null))
        assertEquals(Mg4Variant.STANDARD_51, Mg4Variant.of(50.8, 80.0))
        assertEquals(Mg4Variant.LONG_RANGE_64, Mg4Variant.of(50.8, 130.0))
        assertEquals(Mg4Variant.EXTENDED_77, Mg4Variant.of(74.4, null))
        assertEquals(ChargeCurve.BANDS + 1, Mg4Variant.STANDARD_51.dcKw.size)
    }
}
