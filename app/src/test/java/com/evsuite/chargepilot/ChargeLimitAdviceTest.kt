package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeLimitAdviceTest {
    @Test
    fun `plan warns when charging stop departure exceeds vehicle limit`() {
        assertEquals(
            ChargeLimitAdvice(limitPercent = 80, departurePercent = 92.0),
            ChargeLimitAdvice.of(
                usesDepartureCharge = true,
                departurePercent = 92.0,
                limitPercent = 80,
            ),
        )
    }

    @Test
    fun `sufficient or unreadable limit stays silent`() {
        assertNull(ChargeLimitAdvice.of(true, departurePercent = 80.0, limitPercent = 80))
        assertNull(ChargeLimitAdvice.of(true, departurePercent = 80.0, limitPercent = 90))
        assertNull(ChargeLimitAdvice.of(true, departurePercent = 92.0, limitPercent = null))
    }

    @Test
    fun `plan without charging stop does not claim departure target is needed`() {
        assertNull(ChargeLimitAdvice.of(false, departurePercent = 92.0, limitPercent = 80))
    }
}
