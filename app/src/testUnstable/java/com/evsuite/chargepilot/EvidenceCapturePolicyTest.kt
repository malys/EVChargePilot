package com.evsuite.chargepilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCapturePolicyTest {

    @Test
    fun `unknown and invalid speed fail closed`() {
        assertFalse(EvidenceCapturePolicy.gate(null) == EvidenceCaptureGate.PARKED)
        assertFalse(EvidenceCapturePolicy.gate(Float.NaN) == EvidenceCaptureGate.PARKED)
        assertFalse(EvidenceCapturePolicy.gate(Float.POSITIVE_INFINITY) == EvidenceCaptureGate.PARKED)
    }

    @Test
    fun `capture can change only at the parked threshold`() {
        assertTrue(EvidenceCapturePolicy.gate(0.1f) == EvidenceCaptureGate.PARKED)
        assertTrue(EvidenceCapturePolicy.gate(0.11f) == EvidenceCaptureGate.MOVING)
    }

    @Test
    fun `first capture minute reads at two hundred milliseconds`() {
        assertFalse(EvidenceCapturePolicy.shouldRead(true, 59_999L, 199L))
        assertTrue(EvidenceCapturePolicy.shouldRead(true, 59_999L, 200L))
    }

    @Test
    fun `ordinary capture and dashboard cadence is one second`() {
        assertFalse(EvidenceCapturePolicy.shouldRead(true, 60_000L, 999L))
        assertTrue(EvidenceCapturePolicy.shouldRead(true, 60_000L, 1_000L))
        assertFalse(EvidenceCapturePolicy.shouldRead(false, 0L, 999L))
        assertTrue(EvidenceCapturePolicy.shouldRead(false, 0L, 1_000L))
    }
}
