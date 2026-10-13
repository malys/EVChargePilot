package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class EcoVoiceEventTest {

    @Test
    fun `an outcome prints as one voice_event line`() {
        val event = EcoVoice.Event(
            atMs = 1_700_000_000_000L,
            origin = EcoVoice.Origin.TRIP_END,
            path = EcoVoice.Path.VEHICLE,
            bound = true,
            waitedMs = 300L,
            importance = 125,
        )
        assertEquals(
            "voice_event=1700000000000 origin=trip_end path=vehicle bound=true waited_ms=300 " +
                "importance=125",
            event.line(),
        )
    }
}
