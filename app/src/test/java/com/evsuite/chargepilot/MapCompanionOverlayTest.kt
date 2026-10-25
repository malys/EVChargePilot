package com.evsuite.chargepilot

import com.evsuite.chargepilot.MapCompanionOverlay.LOST_MS
import com.evsuite.chargepilot.MapCompanionOverlay.START_GRACE_MS
import com.evsuite.chargepilot.MapCompanionOverlay.guidanceOver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-094: the companion over the map ends with the route, never on one missed answer. */
class MapCompanionOverlayTest {

    @Test
    fun `a route the car has not confirmed yet gets its grace`() {
        assertFalse(guidanceOver(now = START_GRACE_MS, shownAt = 0, lastGuidingAt = null))
        assertTrue(guidanceOver(now = START_GRACE_MS + 1, shownAt = 0, lastGuidingAt = null))
    }

    @Test
    fun `a cancelled or finished route ends it once the car has been quiet long enough`() {
        assertFalse(guidanceOver(now = 100_000 + LOST_MS, shownAt = 0, lastGuidingAt = 100_000))
        assertTrue(guidanceOver(now = 100_000 + LOST_MS + 1, shownAt = 0, lastGuidingAt = 100_000))
    }

    @Test
    fun `a long drive still guiding is never over`() {
        assertFalse(guidanceOver(now = 3_600_000, shownAt = 0, lastGuidingAt = 3_599_000))
    }
}
