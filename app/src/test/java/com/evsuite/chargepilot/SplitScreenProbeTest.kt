package com.evsuite.chargepilot

import com.evsuite.chargepilot.SplitScreenProbe.Facts
import com.evsuite.chargepilot.SplitScreenProbe.Option
import com.evsuite.chargepilot.SplitScreenProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitScreenProbeTest {

    /** The 2026-10-18 bundle: platform signed, no map activity allows embedding, no route on. */
    private val car = Facts(
        activityView = true,
        secondaryDisplays = true,
        platformSigned = true,
        systemUid = false,
        navAllowsEmbedding = false,
        splitScreenConfig = true,
        navResizeable = true,
        guidanceBound = true,
        navigating = false,
        guidanceAnswers = false,
    )

    @Test fun `a platform-signed host can embed a map that never asked to be embedded`() {
        val options = SplitScreenProbe.options(car)
        assertEquals("possible_if_declared: INTERNAL_SYSTEM_WINDOW", options[Option.MAP_SYSTEM_WINDOW])
        assertTrue(options[Option.MAP_ALLOWED_EMBEDDING]!!.startsWith("no:"))
        assertEquals(Verdict.MAP_EMBEDDABLE, SplitScreenProbe.verdict(options))
    }

    @Test fun `without secondary-display activities no embedding route is open`() {
        val options = SplitScreenProbe.options(car.copy(secondaryDisplays = false))
        assertTrue(options[Option.MAP_SYSTEM_WINDOW]!!.startsWith("no:"))
        assertEquals(Verdict.UNPROVEN, SplitScreenProbe.verdict(options))
    }

    @Test fun `guidance is proven only while a route is running`() {
        val unsigned = car.copy(platformSigned = false)
        assertEquals(Verdict.UNPROVEN, SplitScreenProbe.verdict(SplitScreenProbe.options(unsigned)))
        val driving = unsigned.copy(navigating = true, guidanceAnswers = true)
        assertEquals(Verdict.GUIDANCE_ONLY, SplitScreenProbe.verdict(SplitScreenProbe.options(driving)))
        val unbound = unsigned.copy(guidanceBound = false)
        assertEquals(Verdict.NEITHER, SplitScreenProbe.verdict(SplitScreenProbe.options(unbound)))
    }
}
