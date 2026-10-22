package com.evsuite.chargepilot

import com.evsuite.chargepilot.route.OpenChargeMap
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.hardware.telemetry.SocRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which point the car is actually sent to.
 *
 * The mistake this guards costs nothing to make and is invisible until someone arrives under
 * their reserve: send the destination where the charging stop was meant, and the car picks its
 * own road while every figure on the screen goes on describing a different trip.
 */
class ChargeStopHandoffTest {

    private val marseille = OrsGeocode.Place("Marseille", 5.3698, 43.2965)

    private fun charger(
        name: String = "Aire de Montélimar",
        latitude: Double = 44.5,
        longitude: Double = 4.75,
    ) = OpenChargeMap.Charger(
        name = name,
        longitude = longitude,
        latitude = latitude,
        powerKw = 150.0,
        connectors = listOf("CCS (Type 2)"),
        operator = null,
        dataProvider = null,
        licence = null,
        verifiedAt = null,
        operational = true,
    )

    private fun found(alongKm: Double = 180.0) = Found(charger(), alongKm, arrivalPercent = 22.0)

    /** The driver's own trips, with a band narrow enough that the planner does not refuse. */
    private fun rate() = SocRate(
        percentPerKm = 0.25,
        uncertaintyPercentPerKm = 0.01,
        source = SocRate.Source.TRIP_HISTORY,
        sampleCount = 30,
    )

    @Test
    fun `a trip that needs a stop sends the stop, not the destination`() {
        val handoff = ChargeStopHandoff.of(marseille, listOf(found()))

        assertTrue(handoff.toStop)
        // The single-point channels — geo: and the adapter's goTo — get the charger.
        assertEquals(44.5, handoff.latitude, 1e-9)
        assertEquals(4.75, handoff.longitude, 1e-9)
        assertEquals("Aire de Montélimar", handoff.label)
        assertEquals("Aire de Montélimar", handoff.point?.name)

        // The route channel gets the whole plan: the stop as a waypoint, Marseille as the end.
        assertEquals(listOf("Aire de Montélimar"), handoff.pathway.map { it.name })
        assertEquals("Marseille", handoff.destination?.name)
    }

    @Test
    fun `the pathway is the stops alone, never points along the road`() {
        // 2026-10-19: with shape points, Telenav guided to the next one and dropped guidance
        // about 2 km in. Only places the driver stops at go in the pathway.
        val handoff = ChargeStopHandoff.of(marseille, listOf(found(alongKm = 180.0)))

        assertEquals(listOf("Aire de Montélimar"), handoff.pathway.map { it.name })
        assertEquals(listOf("Aire de Montélimar", "Marseille"), handoff.legs.map { it.name })
    }

    /** CP-062: Auzielle → Paris stops twice, and the car is told about both stops. */
    @Test
    fun `every stop of a long trip is a waypoint and a leg, in the order they are driven`() {
        val handoff = ChargeStopHandoff.of(
            marseille,
            listOf(
                Found(charger(name = "Brive", latitude = 45.15, longitude = 1.53), 220.0, 15.0),
                Found(charger(name = "Vierzon", latitude = 47.22, longitude = 2.07), 440.0, 14.0),
            ),
        )

        assertEquals(listOf("Brive", "Vierzon", "Marseille"), handoff.legs.map { it.name })
        // Both stops are in the pathway, in road order.
        assertEquals(listOf("Brive", "Vierzon"), handoff.pathway.map { it.name })
        // The single-point channels still get the first stop: it is the leg about to be driven.
        assertEquals("Brive", handoff.label)
        assertEquals("Brive", handoff.point?.name)
    }

    @Test
    fun `a trip with no stop sends no waypoint`() {
        val handoff = ChargeStopHandoff.of(marseille, stops = emptyList())

        assertFalse(handoff.toStop)
        assertTrue(handoff.pathway.isEmpty())
        assertEquals(listOf("Marseille"), handoff.legs.map { it.name })
    }

    @Test
    fun `the legs are in the order they are driven, the stop first`() {
        val legs = ChargeStopHandoff.of(marseille, listOf(found())).legs
        assertEquals(listOf("Aire de Montélimar", "Marseille"), legs.map { it.name })
    }

    @Test
    fun `a trip that needs no stop is one leg to the destination`() {
        val handoff = ChargeStopHandoff.of(marseille, stops = emptyList())

        assertFalse(handoff.toStop)
        assertEquals(43.2965, handoff.latitude, 1e-9)
        assertEquals(5.3698, handoff.longitude, 1e-9)
        assertEquals(listOf("Marseille"), handoff.legs.map { it.name })
        assertTrue(handoff.pathway.isEmpty())
    }

    @Test
    fun `an unplanned destination carries no pathway and claims no stop`() {
        val handoff = ChargeStopHandoff.unplanned(marseille)

        assertFalse(handoff.toStop)
        assertTrue(handoff.pathway.isEmpty())
        assertEquals(listOf("Marseille"), handoff.legs.map { it.name })
        assertEquals("Marseille", handoff.point?.name)
    }

    @Test
    fun `a point the adapter would refuse drops out of the lists, never out of the plan`() {
        // A charger whose coordinates are not a place on Earth: the validated POI lists lose it,
        // and the raw numbers stay so the geo: fallback still has somewhere to aim.
        val handoff = ChargeStopHandoff.of(marseille, listOf(Found(charger(latitude = 999.0), 10.0, null)))

        assertTrue(handoff.toStop)
        assertEquals(999.0, handoff.latitude, 1e-9)
        assertNull(handoff.point)
        assertTrue(handoff.pathway.isEmpty())
        assertEquals(listOf("Marseille"), handoff.legs.map { it.name })
    }

    @Test
    fun `goTo is the fallback for a route taken and ignored, never a second destination`() {
        // 2026-10-22: already guiding, route taken, and goTo went out on top of it.
        assertFalse(ChargeStopHandoff.sendsGoTo(routeTaken = true, wasGuiding = true, routeGuided = false))
        assertFalse(ChargeStopHandoff.sendsGoTo(routeTaken = true, wasGuiding = false, routeGuided = true))
        assertTrue(ChargeStopHandoff.sendsGoTo(routeTaken = true, wasGuiding = false, routeGuided = false))
        assertTrue(ChargeStopHandoff.sendsGoTo(routeTaken = false, wasGuiding = true, routeGuided = false))
    }

    @Test
    fun `slowing down removes the stop only when the freed charge actually covers the road`() {
        val rate = rate()

        // 200 km at 0.25 %/km is 50 % of charge, and the reserve wants 10 % left over.
        assertFalse(
            ChargeStopHandoff.removesStop(55.0, savedPercent = 0.0, 200.0, rate, 10.0, null)
        )
        assertTrue(
            ChargeStopHandoff.removesStop(55.0, savedPercent = 8.0, 200.0, rate, 10.0, null)
        )
    }

    @Test
    fun `no charge reading removes nothing, rather than removing everything`() {
        val rate = rate()
        assertFalse(ChargeStopHandoff.removesStop(null, 50.0, 10.0, rate, 10.0, null))
    }

    @Test
    fun `freed charge cannot push the pack past full`() {
        val rate = rate()
        // 400 km needs more than a full pack however much slowing down is claimed to free.
        assertFalse(ChargeStopHandoff.removesStop(90.0, 500.0, 400.0, rate, 10.0, null))
    }
}
