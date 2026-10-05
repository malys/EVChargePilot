package com.evsuite.chargepilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationProbeTest {
    @Test
    fun `ready only once every question has answered`() {
        val all = ValidationQuestion.entries.toSet()
        assertFalse(ValidationProbe.allAnswered(emptySet()))
        assertFalse(ValidationProbe.allAnswered(all - ValidationQuestion.CHARGERS))
        assertTrue(ValidationProbe.allAnswered(all))
    }
}
