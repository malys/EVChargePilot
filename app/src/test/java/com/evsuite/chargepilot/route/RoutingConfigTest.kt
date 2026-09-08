package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingConfigTest {

    @Test
    fun `a file dropped on a usb stick configures the app`() {
        val config = RoutingConfig.parse(
            """
            # EVChargePilot routing
            ors_api_key  = 5b3ce3597851110001cf6248abc
            ors_base_url = https://api.openrouteservice.org
            """.trimIndent()
        )
        assertEquals("5b3ce3597851110001cf6248abc", config.apiKey)
        assertEquals("https://api.openrouteservice.org", config.baseUrl)
    }

    /** A base64-shaped key ends in `=`; splitting on the last separator would eat it. */
    @Test
    fun `both services are configured by one file, key padding and all`() {
        val config = RoutingConfig.parse(
            """
            # EVChargePilot routing
            ors_api_key  = 5b3ce3597851110001cf6248abc=
            ors_base_url = https://api.heigit.org
            ocm_api_key  = 0a1b2c3d
            ocm_base_url = https://api.openchargemap.io
            """.trimIndent()
        )
        assertEquals("5b3ce3597851110001cf6248abc=", config.apiKey)
        assertEquals("https://api.heigit.org", config.baseUrl)
        assertEquals("0a1b2c3d", config.chargerApiKey)
        assertEquals("https://api.openchargemap.io", config.chargerBaseUrl)
    }

    @Test
    fun `a file with only a key leaves a self-hosted base url alone`() {
        val config = RoutingConfig.parse("ors_api_key = abc")
        assertEquals("abc", config.apiKey)
        assertNull(config.baseUrl)
    }

    @Test
    fun `unknown keys are ignored so one file can configure several apps`() {
        val config = RoutingConfig.parse(
            """
            token = an-abrp-token
            ors_api_key = abc
            future_key = whatever
            """.trimIndent()
        )
        assertEquals("abc", config.apiKey)
    }

    @Test
    fun `a file that is not a config yields nothing`() {
        assertTrue(RoutingConfig.parse("").isEmpty())
        assertTrue(RoutingConfig.parse(" binary").isEmpty())
        assertTrue(RoutingConfig.parse("# only a comment").isEmpty())
        assertTrue(RoutingConfig.parse("ors_api_key =").isEmpty())
    }

    @Test
    fun `http is refused because a route request carries the driver's position`() {
        assertEquals("must be https", RoutingConfig.refuseBaseUrl("http://api.example.org"))
    }

    @Test
    fun `credentials in the url are refused`() {
        assertEquals(
            "must not carry credentials",
            RoutingConfig.refuseBaseUrl("https://user:pass@api.example.org"),
        )
    }

    @Test
    fun `a base url carrying a query cannot have a path appended safely`() {
        assertEquals(
            "must not carry a query",
            RoutingConfig.refuseBaseUrl("https://api.example.org/?key=leaked"),
        )
    }

    @Test
    fun `a self-hosted instance is accepted, which is what makes self-hosting possible`() {
        assertNull(RoutingConfig.refuseBaseUrl("https://ors.example.internal:8082/ors"))
        assertEquals(
            "https://ors.example.internal:8082/ors",
            RoutingConfig.validBaseUrl("https://ors.example.internal:8082/ors/"),
        )
    }

    @Test
    fun `a refused base url does not reach the store`() {
        assertNull(RoutingConfig.parse("ors_base_url = http://api.example.org").baseUrl)
        assertNotNull(RoutingConfig.parse("ors_base_url = https://api.example.org").baseUrl)
    }

    @Test
    fun `the saved destinations travel in the same file as the keys`() {
        val config = RoutingConfig.parse(
            """
            ocm_api_key  = 0a1b2c3d
            ocm_base_url = https://api.openchargemap.io
            destination.Home = 5.4474,43.5297
            destination.Office, 12 Rue de la Paix = 3.8767,43.6108
            """.trimIndent()
        )

        assertEquals("0a1b2c3d", config.chargerApiKey)
        assertEquals(
            listOf(
                OrsGeocode.Place("Home", 5.4474, 43.5297),
                OrsGeocode.Place("Office, 12 Rue de la Paix", 3.8767, 43.6108),
            ),
            config.favorites,
        )
    }

    @Test
    fun `a label keeps the case it was written in, unlike the setting keys`() {
        assertEquals(
            "Chez Papi",
            RoutingConfig.parse("DESTINATION.Chez Papi = 1.0,43.0").favorites.single().label,
        )
    }

    @Test
    fun `a coordinate that is not a point on Earth is skipped, whatever it parses as`() {
        val text = listOf(
            "destination.Nowhere = NaN,43.5297",
            "destination.Elsewhere = 5.4474,Infinity",
            "destination.Off the map = 999,43.5297",
            "destination.Off the pole = 5.4474,91",
            "destination. = 5.4474,43.5297",
            "destination.Home = 5.4474,43.5297",
        ).joinToString("\n")

        // Every one of the five parses as a line, and none of them is a place: four
        // coordinates that are not on Earth, and one destination with no name to tap.
        assertEquals(
            listOf(OrsGeocode.Place("Home", 5.4474, 43.5297)),
            RoutingConfig.parse(text).favorites,
        )
    }

    @Test
    fun `more destinations than the cap are truncated, not refused`() {
        val text = (1..RoutingConfig.MAX_DESTINATIONS + 10)
            .joinToString("\n") { "destination.Place $it = 1.0,1.0" }

        assertEquals(RoutingConfig.MAX_DESTINATIONS, RoutingConfig.parse(text).favorites.size)
    }

    @Test
    fun `a file holding only destinations is still a file worth importing`() {
        assertFalse(RoutingConfig.parse("destination.Home = 5.4474,43.5297").isEmpty())
        assertTrue(RoutingConfig.parse("nothing here").isEmpty())
    }
}
