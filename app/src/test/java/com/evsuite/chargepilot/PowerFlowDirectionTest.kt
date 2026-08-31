package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerFlowDirectionTest {
    @Test fun `positive power is battery output`() {
        assertEquals(PowerFlowDirection.OUTPUT, powerFlowDirection(42f))
    }

    @Test fun `negative power is regeneration or charging`() {
        assertEquals(PowerFlowDirection.REGENERATION, powerFlowDirection(-28f))
    }

    @Test fun `near zero power is idle`() {
        assertEquals(PowerFlowDirection.IDLE, powerFlowDirection(0.1f))
        assertEquals(PowerFlowDirection.IDLE, powerFlowDirection(-0.1f))
    }

    @Test fun `missing and invalid power are unavailable`() {
        assertEquals(PowerFlowDirection.UNAVAILABLE, powerFlowDirection(null))
        assertEquals(PowerFlowDirection.UNAVAILABLE, powerFlowDirection(Float.NaN))
    }
}
