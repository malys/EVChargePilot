package com.evsuite.chargepilot

import org.junit.Assert.assertTrue
import org.junit.Test

class EcoCoachDefaultsTest {
    @Test
    fun `unstable shows eco advice without setup`() {
        assertTrue(EcoCoachDefaults.ADVICE_ENABLED)
    }
}
