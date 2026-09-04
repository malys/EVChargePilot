package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
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
}
