package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CP-094: the split screen's distance left, which the car only gives up to its next waypoint. */
class NavSplitDestinationTest {

    @Test
    fun `without a stop the car's own figure is the destination`() {
        assertEquals(4.2, NavSplitActivity.destinationKm(false, 4.2, 7.0, 1.0)!!, 1e-9)
        assertNull(NavSplitActivity.destinationKm(false, null, 7.0, 1.0))
    }

    @Test
    fun `with a stop the destination is the road's length less what was driven`() {
        // The car says 0.9 km: that is the stop, not the 7 km trip.
        assertEquals(5.5, NavSplitActivity.destinationKm(true, 0.9, 7.0, 1.5)!!, 1e-9)
        assertEquals(0.0, NavSplitActivity.destinationKm(true, 0.0, 7.0, 9.0)!!, 1e-9)
        assertNull(NavSplitActivity.destinationKm(true, 0.9, null, 1.5))
    }
}
