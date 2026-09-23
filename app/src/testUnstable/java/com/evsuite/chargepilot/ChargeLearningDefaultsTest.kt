package com.evsuite.chargepilot

import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeLearningDefaultsTest {
    @Test
    fun `unstable refreshes battery learning after ledger writes`() {
        assertTrue(ChargeLearningDefaults.AUTO_REFRESH)
    }
}
