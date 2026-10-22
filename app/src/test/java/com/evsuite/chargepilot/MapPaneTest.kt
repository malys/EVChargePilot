package com.evsuite.chargepilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPaneTest {

    @Test
    fun anEmptyPaneIsBlack() {
        assertTrue(MapPane.isBlack(IntArray(4) { 0xFF000000.toInt() }))
        assertTrue(MapPane.isBlack(IntArray(4) { 0xFF0F0F0F.toInt() }))
    }

    @Test
    fun oneLitRoadOnANightMapIsAMap() {
        val pixels = IntArray(4) { 0xFF000000.toInt() }
        pixels[2] = 0xFF305070.toInt()
        assertFalse(MapPane.isBlack(pixels))
    }
}
