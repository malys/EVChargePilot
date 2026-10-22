package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryPageTest {
    @Test
    fun `pages follow visible navigation order and skip diagnostics`() {
        assertEquals(PrimaryPage.ARRIVAL, PrimaryPage.ENERGY.neighbour(1))
        assertEquals(PrimaryPage.TRIPS, PrimaryPage.ARRIVAL.neighbour(1))
        assertEquals(PrimaryPage.BATTERY, PrimaryPage.TRIPS.neighbour(1))
        assertEquals(PrimaryPage.SETTINGS, PrimaryPage.BATTERY.neighbour(1))
        assertEquals(PrimaryPage.BATTERY, PrimaryPage.SETTINGS.neighbour(-1))
        assertEquals(PrimaryPage.ENERGY, PrimaryPage.ARRIVAL.neighbour(-1))
        assertNull(PrimaryPage.ENERGY.neighbour(-1))
        assertNull(PrimaryPage.SETTINGS.neighbour(1))
        // Reached by the three taps only; a swipe from it goes nowhere.
        assertNull(PrimaryPage.DIAGNOSTICS.neighbour(1))
    }

    @Test
    fun `three quick taps open diagnostics, slow ones do not`() {
        val taps = DiagnosticsTaps(windowMs = 2_000L)
        assertFalse(taps.onTap(0L))
        assertFalse(taps.onTap(500L))
        assertTrue(taps.onTap(900L))
        // The count starts again after an opening.
        assertFalse(taps.onTap(1_000L))
        assertFalse(taps.onTap(1_100L))
        // Too slow: the window restarts at the late tap.
        assertFalse(taps.onTap(5_000L))
        assertFalse(taps.onTap(5_100L))
        assertTrue(taps.onTap(5_200L))
    }
}
