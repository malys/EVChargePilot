package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedDeletionPolicyTest {
    @Test fun `unreadable and invalid speed fail closed`() {
        assertEquals(ParkedDeletionGate.SPEED_UNAVAILABLE, gate(null))
        assertEquals(ParkedDeletionGate.SPEED_UNAVAILABLE, gate(Float.NaN))
        assertEquals(ParkedDeletionGate.SPEED_UNAVAILABLE, gate(Float.POSITIVE_INFINITY))
        assertEquals(ParkedDeletionGate.SPEED_UNAVAILABLE, gate(-1f))
    }

    @Test fun `only the parked threshold permits deletion`() {
        assertEquals(ParkedDeletionGate.PARKED, gate(0f))
        assertEquals(ParkedDeletionGate.PARKED, gate(0.1f))
        assertEquals(ParkedDeletionGate.MOVING, gate(0.1001f))
        assertEquals(ParkedDeletionGate.MOVING, gate(30f))
    }

    @Test fun `missing future and stale observations fail closed`() {
        assertEquals(
            ParkedDeletionGate.SPEED_UNAVAILABLE,
            ParkedDeletionPolicy.gate(0f, null, NOW_MS),
        )
        assertEquals(
            ParkedDeletionGate.SPEED_UNAVAILABLE,
            ParkedDeletionPolicy.gate(0f, NOW_MS + 1L, NOW_MS),
        )
        assertEquals(
            ParkedDeletionGate.PARKED,
            ParkedDeletionPolicy.gate(
                0f,
                NOW_MS - ParkedDeletionPolicy.MAX_READING_AGE_MS,
                NOW_MS,
            ),
        )
        assertEquals(
            ParkedDeletionGate.SPEED_UNAVAILABLE,
            ParkedDeletionPolicy.gate(
                0f,
                NOW_MS - ParkedDeletionPolicy.MAX_READING_AGE_MS - 1L,
                NOW_MS,
            ),
        )
    }

    private fun gate(speedKmh: Float?) = ParkedDeletionPolicy.gate(speedKmh, NOW_MS, NOW_MS)

    private companion object {
        const val NOW_MS = 10_000L
    }
}
