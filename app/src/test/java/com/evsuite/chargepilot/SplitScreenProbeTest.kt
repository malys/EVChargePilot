package com.evsuite.chargepilot

import com.evsuite.chargepilot.SplitScreenProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitScreenProbeTest {

    @Test fun `the map wins only when both the host and the map allow embedding`() {
        assertEquals(Verdict.MAP_EMBEDDABLE, SplitScreenProbe.verdict(true, true, false))
        assertEquals(Verdict.GUIDANCE_ONLY, SplitScreenProbe.verdict(true, false, true))
        assertEquals(Verdict.GUIDANCE_ONLY, SplitScreenProbe.verdict(false, true, true))
    }

    @Test fun `nothing usable is said plainly`() {
        assertEquals(Verdict.NEITHER, SplitScreenProbe.verdict(false, true, false))
        assertEquals(Verdict.NEITHER, SplitScreenProbe.verdict(true, false, false))
    }
}
