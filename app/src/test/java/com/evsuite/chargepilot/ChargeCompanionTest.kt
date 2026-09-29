package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryChargeSession
import com.evsuite.hardware.telemetry.ChargeCounterBehaviour
import com.evsuite.hardware.telemetry.ChargeDurationResult
import com.evsuite.hardware.telemetry.ChargeEnergy
import com.evsuite.hardware.telemetry.ChargePowerStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeCompanionTest {

    private val minute = 60_000L
    private val t0 = 10_000 * minute

    /** A past charge 20 → 100 % at three minutes a point, long before [t0]. */
    private fun past() = ChargeEnergy(
        session = BatteryChargeSession(0L, 1L, 20.0, 100.0, 0.0, 0.0, 15.0),
        watched = true,
        maxEntryGapMs = 0L,
        packDeltaKwh = null,
        meanPowerKw = null,
        counterBehaviour = ChargeCounterBehaviour.RESET,
        consumedDeltaKwh = null,
        regeneratedDeltaKwh = null,
        movedKm = 0.0,
        chargingStatuses = emptyList(),
        steps = (20 until 100).map { ChargePowerStep(it.toDouble(), it + 1.0, 3 * minute, 7.0) },
        minOutsideTempCelsius = 15.0,
        maxOutsideTempCelsius = 15.0,
    )

    private fun companion(limit: Int? = null) = ChargeCompanion({ List(3) { past() } }, { limit })

    @Test
    fun `a rise with the car still opens a charge, an unreadable speed does not`() {
        val blind = companion()
        blind.observe(t0, 45.0, null, 15.0)
        assertTrue(blind.observe(t0 + minute, 47.0, null, 15.0).isEmpty())
        assertNull(blind.live)

        val c = companion(limit = 80)
        c.observe(t0, 45.0, 0f, 15.0)
        c.observe(t0 + minute, 45.5, 0f, 15.0)
        val started = c.observe(t0 + 2 * minute, 46.0, 0f, 15.0).single() as ChargeCompanion.Event.Started
        assertEquals(t0, started.live.startedAtMs)
        assertEquals(80, started.live.targetPercent)
        val estimate = (started.live.result as ChargeDurationResult.Ready).estimate
        assertEquals(34 * 3.0, estimate.minutes, 1e-6)
    }

    @Test
    fun `a whole band slower than every past charge is said, and the end sums the charge`() {
        val c = companion()
        c.observe(t0, 45.0, 0f, 15.0)
        var at = t0
        val events = (46..60).flatMap { soc ->
            at += 5 * minute
            c.observe(at, soc.toDouble(), 0f, 15.0)
        }
        val slower = events.filterIsInstance<ChargeCompanion.Event.Slower>().single()
        assertEquals(50, slower.check.bandFromPercent)
        assertEquals(5.0, slower.check.minutesPerPoint, 1e-9)
        assertTrue(c.observe(at + 30 * minute, 60.0, 0f, 15.0).isEmpty())

        val ended = c.observe(at + 46 * minute, 60.0, 0f, 15.0).single() as ChargeCompanion.Event.Ended
        assertEquals(15.0, ended.pointsAdded, 1e-9)
        assertEquals(at - t0, ended.durationMs)
        assertEquals(1, ended.checkedBands)
        assertEquals(1, ended.slowerBands)
        assertNull(c.live)
    }

    @Test
    fun `moving closes the charge, and the drive opens nothing`() {
        val c = companion()
        c.observe(t0, 45.0, 0f, null)
        c.observe(t0 + minute, 47.0, 0f, null)
        assertTrue(c.observe(t0 + 2 * minute, 47.0, 30f, null).single() is ChargeCompanion.Event.Ended)
        assertTrue(c.observe(t0 + 3 * minute, 49.0, 30f, null).isEmpty())
        assertNull(c.live)
    }
}
