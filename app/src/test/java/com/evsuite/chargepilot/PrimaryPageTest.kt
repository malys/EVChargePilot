package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryPageTest {
    @Test
    fun `pages follow visible navigation order`() {
        assertEquals(PrimaryPage.ARRIVAL, PrimaryPage.ENERGY.neighbour(1))
        assertEquals(PrimaryPage.TRIPS, PrimaryPage.ARRIVAL.neighbour(1))
        assertEquals(PrimaryPage.DIAGNOSTICS, PrimaryPage.TRIPS.neighbour(1))
        assertEquals(PrimaryPage.SETTINGS, PrimaryPage.DIAGNOSTICS.neighbour(1))
        assertEquals(PrimaryPage.ENERGY, PrimaryPage.ARRIVAL.neighbour(-1))
        assertNull(PrimaryPage.ENERGY.neighbour(-1))
        assertNull(PrimaryPage.SETTINGS.neighbour(1))
    }
}
