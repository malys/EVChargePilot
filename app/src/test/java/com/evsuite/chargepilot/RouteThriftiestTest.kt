package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteThriftiestTest {

    private val routed = 0.0..0.0

    @Test
    fun `a road whose worst case beats every other's best is the thriftiest`() {
        assertEquals(2, ChargeStopActivity.thriftiest(listOf(routed, 0.2..0.8, 1.0..1.6)))
    }

    @Test
    fun `the routed road wins when every other road costs more even at its best`() {
        assertEquals(0, ChargeStopActivity.thriftiest(listOf(routed, -2.0..-0.5)))
    }

    @Test
    fun `overlapping bands leave no road labelled`() {
        assertNull(ChargeStopActivity.thriftiest(listOf(routed, -0.4..0.6)))
        assertNull(ChargeStopActivity.thriftiest(listOf(routed, 0.5..1.2, 1.0..1.6)))
    }

    @Test
    fun `the routed road alone has nothing to beat`() {
        assertNull(ChargeStopActivity.thriftiest(listOf(routed)))
    }
}
