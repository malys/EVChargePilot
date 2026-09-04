package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenChargeMapTest {

    private fun poi(
        title: String,
        connectionTypeId: Int,
        powerKw: Double,
        statusTypeId: Int = 50,
        verified: String? = "2026-06-01T10:00:00Z",
        statusUpdate: String = "2026-07-02T08:00:00Z",
    ): String = """
        {
          "AddressInfo": { "Title": "$title", "Latitude": 45.5, "Longitude": 4.0 },
          "StatusTypeID": $statusTypeId,
          "StatusType": { "IsOperational": ${statusTypeId == 50} },
          "DateLastStatusUpdate": "$statusUpdate",
          ${verified?.let { """"DateLastVerified": "$it",""" }.orEmpty()}
          "OperatorInfo": { "Title": "Some Network" },
          "DataProvider": { "Title": "Open Charge Map Contributors" },
          "Connections": [
            {
              "ConnectionTypeID": $connectionTypeId,
              "ConnectionType": { "Title": "CCS (Type 2)" },
              "StatusTypeID": 50,
              "PowerKW": $powerKw
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a charger comes back with what is needed to show it honestly`() {
        val chargers = OpenChargeMap.parse("[${poi("Aire de Montélimar", 33, 150.0)}]")
        assertEquals(1, chargers.size)
        val charger = chargers.first()
        assertEquals("Aire de Montélimar", charger.name)
        assertEquals(150.0, charger.powerKw!!, 1e-9)
        assertEquals(listOf("CCS (Type 2)"), charger.connectors)
        assertEquals("Some Network", charger.operator)
        assertEquals("Open Charge Map Contributors", charger.dataProvider)
        assertEquals("2026-06-01T10:00:00Z", charger.verifiedAt)
        assertEquals(true, charger.operational)
    }

    @Test
    fun `a connector the car cannot use is not a charger this car can stop at`() {
        // CHAdeMO (2) and CCS Type 1 (32) are not MG4 connectors.
        assertTrue(OpenChargeMap.parse("[${poi("CHAdeMO only", 2, 50.0)}]").isEmpty())
        assertTrue(OpenChargeMap.parse("[${poi("Type 1 combo", 32, 50.0)}]").isEmpty())
        assertEquals(1, OpenChargeMap.parse("[${poi("Type 2 socket", 25, 22.0)}]").size)
        assertEquals(1, OpenChargeMap.parse("[${poi("Type 2 tethered", 1036, 7.4)}]").size)
    }

    @Test
    fun `a decommissioned or planned site is never offered`() {
        for (status in listOf(100, 150, 200, 210)) {
            assertTrue(
                "status $status was offered",
                OpenChargeMap.parse("[${poi("gone", 33, 150.0, statusTypeId = status)}]").isEmpty(),
            )
        }
    }

    @Test
    fun `power below what makes a stop worth making is filtered here, not by the server`() {
        val body = "[${poi("slow", 25, 7.4)},${poi("fast", 33, 150.0)}]"
        val usable = OpenChargeMap.parse(body, minPowerKw = 22.0)
        assertEquals(listOf("fast"), usable.map { it.name })
        assertEquals(2, OpenChargeMap.parse(body).size)
    }

    @Test
    fun `an undated record falls back to when it last moved, never to silence`() {
        val charger = OpenChargeMap.parse("[${poi("undated", 33, 150.0, verified = null)}]").first()
        assertEquals("2026-07-02T08:00:00Z", charger.verifiedAt)
    }

    @Test
    fun `nonsense yields nothing rather than a charger in the sea`() {
        assertTrue(OpenChargeMap.parse("not json").isEmpty())
        assertTrue(OpenChargeMap.parse("").isEmpty())
        assertTrue(OpenChargeMap.parse("""{"features":[]}""").isEmpty())
        assertTrue(
            OpenChargeMap.parse(
                """[{"AddressInfo":{"Title":"nowhere","Latitude":999,"Longitude":4.0},
                   "Connections":[{"ConnectionTypeID":33,"PowerKW":50}]}]"""
            ).isEmpty()
        )
    }

    @Test
    fun `the query carries a stretch of road, a corridor and a cap — and no key`() {
        val window = listOf(
            OrsDirections.Point(4.0, 45.0, null),
            OrsDirections.Point(4.0, 45.1, null),
        )
        val query = OpenChargeMap.query(window)
        assertEquals(RouteGeometry.encodePolyline(window), query["polyline"])
        assertEquals("5", query["distance"])
        assertEquals("KM", query["distanceunit"])
        assertEquals(OpenChargeMap.MAX_RESULTS.toString(), query["maxresults"])
        assertNull(query["key"])
        assertNull(query["api_key"])
    }
}
