package com.evsuite.chargepilot

import com.evsuite.hardware.saic.NavGuidance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuidanceRecorderTest {

    @Test
    fun `a firmware that publishes no callbacks is still guiding`() {
        // The 2026-09-04 SWI68 line, field for field: n=0 with a live route behind it.
        val fromGetters = NavGuidance(
            guideStatus = 1,
            remainingDistanceRaw = 9788,
            remainingMinutes = 25,
            road = "Rue de la Fontaine",
            events = 0,
        )

        assertTrue(isGuiding(fromGetters))
    }

    @Test
    fun `an empty reading is not a trip`() {
        assertFalse(isGuiding(NavGuidance.EMPTY))
    }

    @Test
    fun `a status code on its own is not a trip, because no code is known to mean idle`() {
        assertFalse(isGuiding(NavGuidance(guideStatus = 0)))
        assertFalse(isGuiding(NavGuidance(guideStatus = 1)))
    }

    @Test
    fun `a callback is still a trip, on a firmware that sends them`() {
        assertTrue(isGuiding(NavGuidance(events = 1)))
    }
}
