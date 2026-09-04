package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrsDirectionsTest {

    /** The same shape as [response], with the geometry the test cares about. */
    private fun responseWith(coordinates: String): String = """
        {
          "type": "FeatureCollection",
          "metadata": { "attribution": "openrouteservice.org" },
          "features": [
            {
              "type": "Feature",
              "properties": { "summary": { "distance": 40.0, "duration": 1800.0 } },
              "geometry": { "type": "LineString", "coordinates": [ $coordinates ] }
            }
          ]
        }
    """.trimIndent()

    /** Shaped like a real `geojson` answer, cut down to the fields this app reads. */
    private val response = """
        {
          "type": "FeatureCollection",
          "metadata": {
            "attribution": "openrouteservice.org | OpenStreetMap contributors",
            "service": "routing"
          },
          "features": [
            {
              "type": "Feature",
              "properties": { "summary": { "distance": 12.5, "duration": 900.0 } },
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [4.8357, 45.7640, 170.0],
                  [4.8400, 45.7700, 190.0],
                  [4.8500, 45.7800, 150.0]
                ]
              }
            },
            {
              "type": "Feature",
              "properties": { "summary": { "distance": 14.0, "duration": 1200.0 } },
              "geometry": {
                "type": "LineString",
                "coordinates": [ [4.8357, 45.7640, 170.0], [4.8600, 45.7900, 200.0] ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a captured response yields the distance, the duration and the geometry`() {
        val routes = OrsDirections.parse(response)
        assertEquals(2, routes.size)
        val best = routes.first()
        assertEquals(12.5, best.distanceKm, 1e-9)
        assertEquals(15.0, best.durationMinutes, 1e-9)
        assertEquals(3, best.points.size)
        assertEquals(4.8357, best.points.first().longitude, 1e-9)
        assertEquals(45.7640, best.points.first().latitude, 1e-9)
    }

    @Test
    fun `elevation is why this endpoint was chosen`() {
        val best = OrsDirections.parse(response).first()
        assertEquals(170.0, best.points.first().altitudeMetres!!, 1e-9)
        assertEquals(20.0, best.ascentMetres!!, 1e-9)
        assertEquals(40.0, best.descentMetres!!, 1e-9)
    }

    @Test
    fun `terrain noise is not a mountain`() {
        // A flat road sampled by a terrain model wobbles a metre or two at every point. Summed
        // naively that is 60 m of climb over 30 points, and a forecast that believes it will
        // send someone to a charger they did not need.
        val wobbling = (0 until 30).joinToString(",") { index ->
            "[4.0, ${45.0 + index * 0.01}, ${100.0 + if (index % 2 == 0) 2.0 else -2.0}]"
        }
        val route = OrsDirections.parse(responseWith(wobbling)).first()
        assertEquals(0.0, route.ascentMetres!!, 1e-9)
        assertEquals(0.0, route.descentMetres!!, 1e-9)

        // The same points with a real climb in the middle keep the climb.
        val col = "[4.0, 45.0, 100.0],[4.0, 45.1, 101.0],[4.0, 45.2, 900.0],[4.0, 45.3, 899.0]"
        val overACol = OrsDirections.parse(responseWith(col)).first()
        assertEquals(800.0, overACol.ascentMetres!!, 1e-9)
        assertEquals(0.0, overACol.descentMetres!!, 1e-9)
    }

    @Test
    fun `the attribution travels with the route, because the licence requires showing it`() {
        assertTrue(
            OrsDirections.parse(response).all {
                it.attribution == "openrouteservice.org | OpenStreetMap contributors"
            }
        )
    }

    @Test
    fun `a route without elevation reports no climb rather than a flat one`() {
        val flat = OrsDirections.parse(
            """
            {"features":[{"properties":{"summary":{"distance":3.0,"duration":300.0}},
             "geometry":{"coordinates":[[4.0,45.0],[4.1,45.1]]}}]}
            """.trimIndent()
        ).single()
        assertNull(flat.points.first().altitudeMetres)
        assertNull(flat.ascentMetres)
    }

    @Test
    fun `a malformed answer yields nothing rather than half a route`() {
        assertTrue(OrsDirections.parse("").isEmpty())
        assertTrue(OrsDirections.parse("not json").isEmpty())
        assertTrue(OrsDirections.parse("""{"error":{"code":2003}}""").isEmpty())
        // A feature with a geometry and no summary is not a route.
        assertTrue(
            OrsDirections.parse(
                """{"features":[{"geometry":{"coordinates":[[4.0,45.0],[4.1,45.1]]}}]}"""
            ).isEmpty()
        )
        // One point is a position, not a route.
        assertTrue(
            OrsDirections.parse(
                """{"features":[{"properties":{"summary":{"distance":1.0,"duration":60.0}},
                   "geometry":{"coordinates":[[4.0,45.0]]}}]}"""
            ).isEmpty()
        )
    }

    @Test
    fun `the request asks for elevation and no turn instructions`() {
        val body = OrsDirections.requestBody(
            OrsDirections.Point(4.8357, 45.7640, null),
            OrsDirections.Point(5.3698, 43.2965, null),
        )
        assertTrue(body.contains("\"elevation\":true"))
        assertTrue(body.contains("\"instructions\":false"))
        assertTrue(body.contains("\"units\":\"km\""))
        assertTrue(body.contains("[[4.8357,45.764],[5.3698,43.2965]]"))
        assertTrue("alternatives cost parsing, so they are opt-in", !body.contains("alternative"))
    }

    @Test
    fun `alternatives are asked for only when they are wanted`() {
        val body = OrsDirections.requestBody(
            OrsDirections.Point(4.8357, 45.7640, null),
            OrsDirections.Point(5.3698, 43.2965, null),
            alternatives = 3,
        )
        assertTrue(body.contains("\"target_count\":3"))
        assertNotNull(OrsDirections.parse(response).getOrNull(1))
    }
}
