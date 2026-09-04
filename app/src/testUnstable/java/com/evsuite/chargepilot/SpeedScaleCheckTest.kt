package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedScaleCheckTest {

    @Test fun `the reported Auzielle drive reads as the m per s factor`() {
        // 2.4 km on the road, "more than 7 km" in the app.
        val result = SpeedScaleCheck.of(odometerSpanKm = 2.4, integratedKm = 8.6)
        assertEquals(SpeedScaleCheck.Verdict.SPEED_SCALED_BY_3_6, result.verdict)
        assertTrue(result.note.contains("3.6x"))
    }

    @Test fun `agreeing distances clear the conversion`() {
        val result = SpeedScaleCheck.of(odometerSpanKm = 12.0, integratedKm = 12.4)
        assertEquals(SpeedScaleCheck.Verdict.CONSISTENT, result.verdict)
    }

    @Test fun `a disagreement that is not the factor is not blamed on the factor`() {
        val result = SpeedScaleCheck.of(odometerSpanKm = 10.0, integratedKm = 18.0)
        assertEquals(SpeedScaleCheck.Verdict.DISAGREES, result.verdict)
    }

    @Test fun `too short a drive concludes nothing rather than guessing`() {
        val result = SpeedScaleCheck.of(odometerSpanKm = 1.0, integratedKm = 3.6)
        assertEquals(SpeedScaleCheck.Verdict.INCONCLUSIVE, result.verdict)
        assertNull(result.ratio)
    }

    @Test fun `a missing odometer concludes nothing`() {
        val result = SpeedScaleCheck.of(odometerSpanKm = null, integratedKm = 8.6)
        assertEquals(SpeedScaleCheck.Verdict.INCONCLUSIVE, result.verdict)
        assertNull(result.ratio)
    }

    @Test fun `no trip recorded concludes nothing`() {
        assertEquals(
            SpeedScaleCheck.Verdict.INCONCLUSIVE,
            SpeedScaleCheck.of(odometerSpanKm = 20.0, integratedKm = null).verdict,
        )
    }
}
