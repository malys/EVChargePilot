package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CP-097: the split eco ring's colour on a hard second, by the trip's own rule. */
class HarshPulseTest {

    @Test
    fun `a hard stop shows braking and is held, then clears`() {
        val pulse = HarshPulse()
        assertNull(pulse.add(0L, 50f))
        // 50 to 38 km/h in one second is 3,3 m/s².
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(1_000L, 38f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(2_000L, 37f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(3_999L, 36f))
        assertNull(pulse.add(4_000L, 36f))
    }

    @Test
    fun `a hard start shows acceleration, a calm one nothing`() {
        val pulse = HarshPulse()
        pulse.add(0L, 0f)
        // 0 to 8 km/h is 2,2 m/s²: brisk, not hard.
        assertNull(pulse.add(1_000L, 8f))
        // 8 to 18 km/h is 2,8 m/s².
        assertEquals(HarshPulse.Kind.ACCELERATION, pulse.add(2_000L, 18f))
    }

    @Test
    fun `the latest hard second wins and restarts the hold`() {
        val pulse = HarshPulse()
        pulse.add(0L, 30f)
        assertEquals(HarshPulse.Kind.ACCELERATION, pulse.add(1_000L, 40f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(2_000L, 28f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(4_500L, 28f))
    }

    @Test
    fun `a gap, a missing speed or standing still is never a pulse`() {
        val pulse = HarshPulse()
        pulse.add(0L, 0f)
        // Six seconds unread: the speed change is not one hard second.
        assertNull(pulse.add(6_000L, 60f))
        assertNull(pulse.add(7_000L, null))
        assertNull(pulse.add(8_000L, 20f))
        val still = HarshPulse()
        still.add(0L, 0f)
        assertNull(still.add(1_000L, 0f))
        // A repeated or older timestamp is ignored, not divided by.
        val same = HarshPulse()
        same.add(1_000L, 50f)
        assertNull(same.add(1_000L, 10f))
        assertNull(same.add(500L, 10f))
    }
}
