package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrsGeocodeTest {

    private val response = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "properties": { "label": "Lyon, Auvergne-Rhône-Alpes, France" },
              "geometry": { "type": "Point", "coordinates": [4.8357, 45.7640] }
            },
            {
              "properties": { "label": "Lyon-Saint-Exupéry Airport, France" },
              "geometry": { "type": "Point", "coordinates": [5.0811, 45.7256] }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a search answers with places the driver can choose between`() {
        val places = OrsGeocode.parse(response)
        assertEquals(2, places.size)
        assertEquals("Lyon, Auvergne-Rhône-Alpes, France", places.first().label)
        assertEquals(4.8357, places.first().longitude, 1e-9)
        assertEquals(45.7640, places.first().latitude, 1e-9)
    }

    @Test
    fun `a place off the planet is not a place`() {
        assertTrue(
            OrsGeocode.parse(
                """{"features":[{"properties":{"label":"nowhere"},
                   "geometry":{"coordinates":[999.0,45.0]}}]}"""
            ).isEmpty()
        )
        assertTrue(
            OrsGeocode.parse(
                """{"features":[{"properties":{"label":"unnamed"},
                   "geometry":{"coordinates":[4.0]}}]}"""
            ).isEmpty()
        )
        assertTrue(OrsGeocode.parse("not json").isEmpty())
        assertTrue(OrsGeocode.parse("").isEmpty())
    }

    @Test
    fun `the list is capped at what a driver reads on a head unit`() {
        val many = (1..20).joinToString(",") {
            """{"properties":{"label":"place $it"},"geometry":{"coordinates":[4.0,45.0]}}"""
        }
        assertEquals(
            OrsGeocode.MAX_RESULTS,
            OrsGeocode.parse("""{"features":[$many]}""").size,
        )
    }

    @Test
    fun `the query carries the place and, when known, where the car is`() {
        val plain = OrsGeocode.query("Lyon", null)
        assertEquals("Lyon", plain["text"])
        assertFalse(plain.containsKey("focus.point.lon"))

        val focused = OrsGeocode.query("  Lyon  ", LocationSource.Fix(4.8357, 45.7640, 170.0, 0L))
        assertEquals("Lyon", focused["text"])
        assertEquals("4.8357", focused["focus.point.lon"])
        assertEquals("45.764", focused["focus.point.lat"])
    }

    @Test
    fun `what the driver typed is encoded, never pasted into a URL`() {
        assertEquals(
            "?text=Caf%C3%A9+%26+Co&size=5",
            RoutingTransport.encodeQuery(linkedMapOf("text" to "Café & Co", "size" to "5")),
        )
        assertEquals("", RoutingTransport.encodeQuery(emptyMap()))
    }
}
