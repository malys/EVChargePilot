package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class TripPlotRunsTest {
    @Test fun `a gap in the record splits the curve instead of joining across it`() {
        val runs = TripPlotView.contiguousRuns(
            listOf(1f to 1f, 2f to 2f, null, 4f to 4f, null, null, 7f to 7f),
        )
        assertEquals(3, runs.size)
        assertEquals(listOf(1f to 1f, 2f to 2f), runs[0])
        assertEquals(listOf(4f to 4f), runs[1])
        assertEquals(listOf(7f to 7f), runs[2])
    }

    @Test fun `the cursor clock reads minutes and seconds, and never counts backwards`() {
        assertEquals("0:07", TripPlotView.elapsed(7_400L))
        assertEquals("12:30", TripPlotView.elapsed(750_000L))
        assertEquals("0:00", TripPlotView.elapsed(-1_000L))
    }

    @Test fun `an all-null trace draws nothing`() {
        assertEquals(emptyList<List<Pair<Float, Float>>>(), TripPlotView.contiguousRuns(listOf(null, null)))
    }
}
