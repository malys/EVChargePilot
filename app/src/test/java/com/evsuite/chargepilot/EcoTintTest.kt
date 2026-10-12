package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class EcoTintTest {

    @Test
    fun `no tint before a minute of movement or for a smooth drive`() {
        assertEquals(0f, EcoCoach.harshTint(null))
        assertEquals(0f, EcoCoach.harshTint(0.0))
        assertEquals(0f, EcoCoach.harshTint(6.0))
    }

    @Test
    fun `the tint grows with the harsh share and stops at full`() {
        assertEquals(1f / 3, EcoCoach.harshTint(12.0), 1e-6f)
        assertEquals(2f / 3, EcoCoach.harshTint(18.0), 1e-6f)
        assertEquals(1f, EcoCoach.harshTint(24.0))
        assertEquals(1f, EcoCoach.harshTint(80.0))
    }
}
