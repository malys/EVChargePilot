package com.evsuite.chargepilot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            powerWindows = listOf(listOf(42.0, 13.5, 18.25)),
            summaries = emptyList(),
            drivingStress = listOf(
                TripHistoryArtifact.TripStress(1L, 2.5, null),
                TripHistoryArtifact.TripStress(2L, null, null),
            ),
        )

        val json = JsonParser.parseString(artifact.toJson()).asJsonObject

        assertEquals(4, json["schemaVersion"].asInt)
        assertEquals(
            "used=3/13 segments=7/12 span=18/20 km/h",
            json["socModelFit"].asString,
        )
        assertEquals(18.25, json["powerWindows"].asJsonArray[0].asJsonArray[2].asDouble, 0.0)
        val stress = json["drivingStress"].asJsonArray
        assertEquals(2.5, stress[0].asJsonObject["stressedMinutes"].asDouble, 0.0)
        // A trip the measurement could not judge carries no figure at all, never a zero.
        assertFalse(stress[1].asJsonObject.has("stressedMinutes"))
    }
}
