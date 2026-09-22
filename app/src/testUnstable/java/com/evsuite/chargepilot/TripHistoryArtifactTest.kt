package com.evsuite.chargepilot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TripHistoryArtifactTest {

    @Test
    fun `artifact carries model gate without opening planning screen`() {
        val artifact = TripHistoryArtifact(
            savedAtMs = 1_700_000_000_000L,
            trips = 13,
            speedScaleCheck = SpeedScaleCheck.Result(
                verdict = SpeedScaleCheck.Verdict.CONSISTENT,
                odometerSpanKm = 18.0,
                integratedKm = 17.6,
                ratio = 17.6 / 18.0,
                note = "consistent",
            ),
            socModelFit = "used=3/13 segments=7/12 span=18/20 km/h",
            summaries = emptyList(),
        )

        val json = JsonParser.parseString(artifact.toJson()).asJsonObject

        assertEquals(2, json["schemaVersion"].asInt)
        assertEquals(
            "used=3/13 segments=7/12 span=18/20 km/h",
            json["socModelFit"].asString,
        )
    }
}
