package com.evsuite.chargepilot

import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RoutingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsTransferTest {

    private val everything = SettingsTransfer.Settings(
        routing = RoutingConfig(
            apiKey = "5b3ce3597851110001cf6248abc=",
            baseUrl = "https://api.heigit.org",
            chargerApiKey = "0a1b2c3d",
            chargerBaseUrl = "https://api.openchargemap.io",
            favorites = listOf(
                OrsGeocode.Place("Home", 5.4474, 43.5297),
                OrsGeocode.Place("Office, 12 Rue de la Paix", 3.8767, 43.6108),
            ),
        ),
        vehicle = VehicleSettings.Values(54.0, 92.0, 50.0, 15.0),
    )

    @Test fun `one file carries every setting from one car to the next`() {
        val directory = tempDirectory()

        val written = SettingsTransfer.write(directory, everything)

        assertEquals(SettingsTransfer.FILE_NAME, written?.name)
        assertEquals(everything, SettingsTransfer.read(written!!))
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    /** A base64-shaped key ends in `=`, and JSON carries it without the quoting a text file needs. */
    @Test fun `the file says what it is and warns about the keys it carries`() {
        val text = SettingsTransfer.encode(everything)

        assertTrue(text.contains("\"format\": \"${SettingsTransfer.FORMAT}\""))
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("clear text"))
        assertTrue(text.contains("\"ors_api_key\": \"5b3ce3597851110001cf6248abc=\""))
        assertTrue(text.contains("\"usable_capacity_kwh_when_new\": 54.0"))
        assertTrue(text.contains("\"label\": \"Office, 12 Rue de la Paix\""))
    }

    @Test fun `an export replaces the earlier one instead of piling up`() {
        val directory = tempDirectory()

        SettingsTransfer.write(directory, SettingsTransfer.Settings(RoutingConfig(apiKey = "old")))
        SettingsTransfer.write(directory, SettingsTransfer.Settings(RoutingConfig(apiKey = "new")))

        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(
            "new",
            SettingsTransfer.read(File(directory, SettingsTransfer.FILE_NAME)).routing.apiKey,
        )
    }

    @Test fun `absent sections are left out, so importing back blanks nothing`() {
        val settings = SettingsTransfer.Settings(RoutingConfig(apiKey = "abc"))

        val decoded = SettingsTransfer.decode(SettingsTransfer.encode(settings))

        assertNull(decoded.routing.baseUrl)
        assertNull(decoded.vehicle)
        assertEquals(emptyList<OrsGeocode.Place>(), decoded.routing.favorites)
    }

    @Test fun `a car still on the defaults does not export them over a measured pack`() {
        assertNull(
            SettingsTransfer.decode(
                SettingsTransfer.encode(SettingsTransfer.Settings(RoutingConfig(apiKey = "abc")))
            ).vehicle
        )
    }

    /** The sticks carrying the old `key = value` export are in gloveboxes already. */
    @Test fun `the format it used to export is still imported`() {
        val stick = tempDirectory()
        val legacy = File(stick, "evchargepilot-routing.txt").apply {
            writeText(
                """
                # EVChargePilot routing
                ors_api_key  = 5b3ce35abc
                destination.Home = 5.4474,43.5297
                """.trimIndent()
            )
        }

        val settings = SettingsTransfer.read(legacy)

        assertEquals("5b3ce35abc", settings.routing.apiKey)
        assertEquals(
            listOf(OrsGeocode.Place("Home", 5.4474, 43.5297)),
            settings.routing.favorites,
        )
        assertNull(settings.vehicle)
    }

    @Test fun `a file that is not this app's settings yields nothing to apply`() {
        val stick = tempDirectory()

        assertTrue(SettingsTransfer.read(File(stick, "does-not-exist")).isEmpty())
        assertTrue(SettingsTransfer.read(stick).isEmpty())
        assertTrue(SettingsTransfer.read(file(stick, "photo.jpg", "not a config")).isEmpty())
        assertTrue(SettingsTransfer.decode("{ not json").isEmpty())
        assertTrue(SettingsTransfer.decode("[]").isEmpty())
        // Another app's JSON: the format field is what says this file is ours.
        assertTrue(SettingsTransfer.decode("""{"ors_api_key":"abc"}""").isEmpty())
    }

    @Test fun `a file too big to be settings is never read`() {
        val stick = tempDirectory()
        val oversized = File(stick, "film.mp4")
            .apply { writeBytes(ByteArray(RoutingConfig.MAX_FILE_BYTES + 1)) }

        assertTrue(SettingsTransfer.read(oversized).isEmpty())
    }

    @Test fun `a hand-edited file cannot route the car off the planet`() {
        val decoded = SettingsTransfer.decode(
            """
            {
              "format": "evchargepilot-settings",
              "routing": { "ors_base_url": "http://api.example.org" },
              "destinations": [
                { "label": "Nowhere", "longitude": 999, "latitude": 43.5 },
                { "label": "", "longitude": 5.4474, "latitude": 43.5297 },
                { "label": "Broken", "longitude": "east" },
                { "label": "Home", "longitude": 5.4474, "latitude": 43.5297 }
              ]
            }
            """.trimIndent()
        )

        // http would carry the driver's position in clear, and three of the four destinations
        // are not places: an impossible longitude, no name to tap, and a coordinate that is text.
        assertNull(decoded.routing.baseUrl)
        assertEquals(
            listOf(OrsGeocode.Place("Home", 5.4474, 43.5297)),
            decoded.routing.favorites,
        )
    }

    @Test fun `vehicle figures out of bounds fall back to the documented defaults`() {
        val decoded = SettingsTransfer.decode(
            """
            {
              "format": "evchargepilot-settings",
              "vehicle": { "usable_capacity_kwh_when_new": 0, "state_of_health_percent": 300,
                           "reserve_percent": 15 }
            }
            """.trimIndent()
        )

        assertEquals(
            VehicleSettings.Values(
                usableCapacityKwhWhenNew = VehicleSettings.DEFAULT_CAPACITY_KWH,
                stateOfHealthPercent = VehicleSettings.DEFAULT_HEALTH_PERCENT,
                minChargerPowerKw = VehicleSettings.DEFAULT_MIN_POWER_KW,
                reservePercent = 15.0,
            ),
            decoded.vehicle,
        )
    }

    @Test fun `more destinations than the list holds are truncated, not refused`() {
        val entries = (1..RoutingConfig.MAX_DESTINATIONS + 10)
            .joinToString(",") { """{"label":"Place $it","longitude":1.0,"latitude":1.0}""" }
        val decoded = SettingsTransfer.decode(
            """{"format":"evchargepilot-settings","destinations":[$entries]}"""
        )

        assertEquals(RoutingConfig.MAX_DESTINATIONS, decoded.routing.favorites.size)
    }

    @Test fun `nothing configured writes no file at all`() {
        val directory = tempDirectory()

        assertNull(SettingsTransfer.write(directory, SettingsTransfer.Settings()))
        assertEquals(0, directory.listFiles()!!.size)
    }

    private fun file(directory: File, name: String, content: String): File =
        File(directory, name).apply { writeText(content) }

    private fun tempDirectory(): File = Files.createTempDirectory("settings-transfer").toFile()
}
