package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off the car there is no vendor binder to survey, which is the case worth pinning: the
 * artifact still has to leave, because "the probe ran and found nothing" and "the probe never
 * ran" are different answers and a missing file gives the second one to both.
 */
class RuntimeInterfaceArtifactTest {

    @Test fun `a survey with nothing to survey still writes itself out`() {
        val artifact = RuntimeInterfaceArtifact.of(nowMs = 1_700_000_000_000L)

        assertEquals(RuntimeInterfaceArtifact.PROBE, artifact.probe)
        assertEquals(1, artifact.schemaVersion)
        assertEquals(1_700_000_000_000L, artifact.savedAtMs)
        assertTrue(artifact.notes.isNotEmpty())
    }

    @Test fun `the json names the probe and carries the notes that read it`() {
        val json = RuntimeInterfaceArtifact.of(nowMs = 1L).toJson()

        assertTrue(json.contains("\"probe\":\"runtime-interfaces\""))
        assertTrue(json.contains("\"firmware\""))
        assertTrue(json.contains("\"interfaces\""))
        // The three-verdict distinction is what the whole survey turns on; a bundle that
        // arrived without it explained would be read as a list of failures.
        assertTrue(json.contains("DENIED"))
    }
}
