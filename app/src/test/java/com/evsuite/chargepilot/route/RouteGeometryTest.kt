package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {

    /** A degree of latitude is about 111 km anywhere, which makes the arithmetic checkable. */
    private fun northward(count: Int): List<OrsDirections.Point> =
        (0 until count).map { OrsDirections.Point(4.0, 45.0 + it * 0.1, null) }

    @Test
    fun `distance along the route is the road, summed segment by segment`() {
        val cumulative = RouteGeometry.cumulativeKm(northward(4))
        assertEquals(0.0, cumulative[0], 1e-9)
        assertEquals(11.12, cumulative[1], 0.05)
        assertEquals(33.35, cumulative[3], 0.1)
    }

    @Test
    fun `the window is the stretch asked for and never the whole route`() {
        val points = northward(11)
        val window = RouteGeometry.window(points, 20.0, 60.0)
        val cumulative = RouteGeometry.cumulativeKm(window)

        assertTrue(window.size in 3..5)
        assertTrue(window.first().latitude > points.first().latitude)
        assertTrue(window.last().latitude < points.last().latitude)
        assertTrue(cumulative.last() <= 45.0)

        // A window that is not a window discloses nothing rather than everything.
        assertTrue(RouteGeometry.window(points, 60.0, 20.0).isEmpty())
        assertTrue(RouteGeometry.window(points, 20.0, 20.0).isEmpty())
        assertTrue(RouteGeometry.window(listOf(points.first()), 0.0, 100.0).isEmpty())
    }

    @Test
    fun `a charger is placed along the route, or refused for being off it`() {
        val points = northward(11)

        val onRoute = RouteGeometry.distanceAlongKm(points, 4.0, 45.5, maxOffRouteKm = 5.0)
        assertEquals(55.6, onRoute!!, 0.2)

        // Half a degree of longitude at this latitude is roughly 39 km away from the road.
        assertNull(RouteGeometry.distanceAlongKm(points, 4.5, 45.5, maxOffRouteKm = 5.0))
        assertNull(RouteGeometry.distanceAlongKm(emptyList(), 4.0, 45.0, maxOffRouteKm = 5.0))
    }

    @Test
    fun `the polyline is the one the rest of the world encodes`() {
        // Google's own documented example, which is the only way to know this is right without
        // sending a request and reading someone else's error message.
        val points = listOf(
            OrsDirections.Point(-120.2, 38.5, null),
            OrsDirections.Point(-120.95, 40.7, null),
            OrsDirections.Point(-126.453, 43.252, null),
        )
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", RouteGeometry.encodePolyline(points))
        assertEquals("", RouteGeometry.encodePolyline(emptyList()))
    }
}
