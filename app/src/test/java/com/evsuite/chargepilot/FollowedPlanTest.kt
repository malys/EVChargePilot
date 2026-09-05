package com.evsuite.chargepilot

import com.evsuite.chargepilot.route.OrsDirections
import com.evsuite.chargepilot.route.RouteWhatIf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FollowedPlanTest {

    private fun section(distanceKm: Double, speedKmh: Double) =
        OrsDirections.Section(distanceKm, distanceKm / speedKmh * 60.0, null)

    @Test
    fun `a section list survives the round trip`() {
        val sections = listOf(section(12.5, 130.0), section(3.25, 50.0), section(80.0, 115.0))
        val back = FollowedPlan.decode(FollowedPlan.encode(sections))
        assertEquals(sections.size, back.size)
        sections.zip(back).forEach { (before, after) ->
            assertEquals(before.distanceKm, after.distanceKm, 1e-3)
            assertEquals(before.durationMinutes, after.durationMinutes, 1e-3)
        }
    }

    @Test
    fun `a French locale does not turn the decimal point into a separator`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            val encoded = FollowedPlan.encode(listOf(section(12.5, 130.0)))
            assertTrue(encoded, encoded.startsWith("12.5000:"))
            assertEquals(12.5, FollowedPlan.decode(encoded).single().distanceKm, 1e-6)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a malformed pair drops the whole list rather than half a route`() {
        assertTrue(FollowedPlan.decode("10.0:5.0;broken").isEmpty())
        assertTrue(FollowedPlan.decode("10.0:5.0;-1.0:2.0").isEmpty())
        assertTrue(FollowedPlan.decode("10.0").isEmpty())
        assertTrue(FollowedPlan.decode("").isEmpty())
        assertTrue(FollowedPlan.decode(null).isEmpty())
    }

    @Test
    fun `a route with more sections than the ceiling keeps none of them`() {
        val many = List(FollowedPlan.MAX_SECTIONS + 1) { section(1.0, 100.0) }
        assertEquals("", FollowedPlan.encode(many))
    }

    @Test
    fun `the road ahead drops what is behind and keeps the section the car is inside`() {
        val sections = listOf(section(10.0, 100.0), section(20.0, 130.0), section(5.0, 90.0))
        val ahead = FollowedPlan.ahead(sections, 20.0)
        assertEquals(2, ahead.size)
        // Half of the 20 km motorway section is behind the car.
        assertEquals(10.0, ahead[0].distanceKm, 1e-9)
        // Scaling both halves keeps the pace, which is the only thing CP-049 reads from it.
        assertEquals(130.0, ahead[0].impliedSpeedKmh!!, 1e-9)
        assertEquals(5.0, ahead[1].distanceKm, 1e-9)
    }

    @Test
    fun `nothing driven leaves the road untouched, and everything driven leaves nothing`() {
        val sections = listOf(section(10.0, 100.0), section(20.0, 130.0))
        assertEquals(sections, FollowedPlan.ahead(sections, 0.0))
        assertTrue(FollowedPlan.ahead(sections, 30.0).isEmpty())
    }

    /**
     * The speed-to-restore row is CP-049's calculation on the trimmed road, and CP-058 requires
     * it to cost nothing. Nothing in this path can reach a transport: the sections came out of a
     * preferences string and the model out of recorded trips.
     */
    @Test
    fun `the road ahead still feeds the what-if once the driven part is gone`() {
        val sections = listOf(section(10.0, 60.0), section(120.0, 130.0))
        val ahead = FollowedPlan.ahead(sections, 40.0)
        assertEquals(1, ahead.size)
        assertEquals(90.0, ahead.single().distanceKm, 1e-9)
        // No model, so it refuses — with a reason, which is the other half of the criterion.
        val result = RouteWhatIf.slower(ahead, model = null, outsideTempCelsius = 12.0)
        assertEquals(
            RouteWhatIf.Result.Unavailable(SpeedWhatIfUnavailable.MODEL_NOT_TRAINED),
            result,
        )
    }
}
