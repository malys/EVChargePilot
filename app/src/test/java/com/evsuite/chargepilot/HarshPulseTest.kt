package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CP-097: the split eco ring's colour on a hard second, by the trip's own rule (CP-099 slope). */
class HarshPulseTest {

    @Test
    fun `a hard stop shows braking and is held, then clears`() {
        val pulse = HarshPulse()
        assertNull(pulse.add(0L, 60f))
        // One second is no slope yet; 60 to 40 km/h over two is 2,8 m/s².
        assertNull(pulse.add(1_000L, 50f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(2_000L, 40f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(3_000L, 39.5f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(4_999L, 39f))
        assertNull(pulse.add(5_000L, 38.5f))
    }

    @Test
    fun `a hard start shows acceleration, a calm one nothing`() {
        val pulse = HarshPulse()
        pulse.add(0L, 0f)
        pulse.add(1_000L, 4f)
        // 0 to 8 km/h over two seconds is 1,1 m/s²; 4 to 18 is 1,9: brisk, not hard.
        assertNull(pulse.add(2_000L, 8f))
        assertNull(pulse.add(3_000L, 18f))
        // 8 to 30 km/h over two seconds is 3,1 m/s².
        assertEquals(HarshPulse.Kind.ACCELERATION, pulse.add(4_000L, 30f))
    }

    @Test
    fun `the latest hard second wins and restarts the hold`() {
        val pulse = HarshPulse()
        pulse.add(0L, 30f)
        pulse.add(1_000L, 40f)
        assertEquals(HarshPulse.Kind.ACCELERATION, pulse.add(2_000L, 50f))
        assertEquals(HarshPulse.Kind.ACCELERATION, pulse.add(3_000L, 40f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(4_000L, 28f))
        assertEquals(HarshPulse.Kind.BRAKING, pulse.add(6_500L, 28.1f))
    }

    @Test
    fun `a read that missed the car's update does not flash a hard second on the catch-up`() {
        val pulse = HarshPulse()
        // A steady 2 m/s²: 7,2 km/h a second, but the read at 2 s repeats the one at 1 s.
        pulse.add(0L, 50f)
        pulse.add(1_000L, 57.2f)
        assertNull(pulse.add(2_000L, 57.2f))
        // Read to read that is 4 m/s²; over the two seconds since the last fresh read, 2.
        assertNull(pulse.add(3_000L, 71.6f))
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
        still.add(1_000L, 0f)
        assertNull(still.add(2_000L, 0f))
        // A repeated or older timestamp is ignored, not divided by.
        val same = HarshPulse()
        same.add(0L, 60f)
        same.add(1_000L, 50f)
        assertNull(same.add(1_000L, 10f))
        assertNull(same.add(500L, 10f))
    }
}
