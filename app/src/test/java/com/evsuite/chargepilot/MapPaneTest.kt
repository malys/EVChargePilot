package com.evsuite.chargepilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPaneTest {
    private val lit = intArrayOf(0xFF305070.toInt())
    private val black = intArrayOf(0xFF000000.toInt())

    @Test fun `black after a lit frame still times out, from the new black interval`() {
        val watch = MapFrameWatch(8_000, 0)
        assertFalse(watch.sample(lit, 1_000).expired)
        assertFalse(watch.sample(black, 20_000).expired)
        assertFalse(watch.sample(black, 27_999).expired)
        assertTrue(watch.sample(black, 28_000).expired)
    }

    @Test fun `a recovered frame resets the black interval`() {
        val watch = MapFrameWatch(8_000, 0)
        assertFalse(watch.sample(black, 7_999).expired)
        assertFalse(watch.sample(lit, 8_000).expired)
        assertFalse(watch.sample(null, 30_000).expired)
        assertTrue(watch.sample(null, 38_000).expired)
    }

    @Test fun `an unreadable surface cannot leave an unmonitored black pane`() {
        val watch = MapFrameWatch(8_000, 0)
        assertEquals("no frame", watch.sample(null, 1_000).state)
        assertTrue(watch.sample(null, 8_000).expired)
    }

    @Test fun `a static map is logged once and never treated as black`() {
        val watch = MapFrameWatch(8_000, 0)
        watch.sample(lit, 0)
        watch.sample(lit, 1_000)
        watch.sample(lit, 2_000)
        val frozen = watch.sample(lit, 3_000)
        assertEquals("frozen", frozen.state)
        assertTrue(frozen.changed)
        assertFalse(watch.sample(lit, 20_000).changed)
        assertFalse(watch.sample(lit, 30_000).expired)
    }

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

    @Test fun `the split screen comes back only over the map, never when the top is unknown`() {
        val map = "com.telenav.app.arp"
        assertTrue(MapApps.mapOnTop(listOf("$map/com.telenav.arp.module.map.MainActivity", "x/.Y"), map))
        assertFalse(MapApps.mapOnTop(listOf("com.saicmotor.camera/.RearViewActivity", "$map/.Main"), map))
        assertFalse(MapApps.mapOnTop(null, map))
        assertFalse(MapApps.mapOnTop(emptyList(), map))
        assertFalse(MapApps.mapOnTop(listOf("$map/.Main"), null))
    }
}
