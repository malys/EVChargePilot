package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CP-098: trips filed under the driver EVTasker published, or under nobody. */
class TripDriversTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = TripDriverStore(folder.root.resolve(TripDriverStore.FILE_NAME))

    private fun trip(startedAtMs: Long) = StoredTrip(
        EnergyTripSummary(
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 600_000L,
            durationMs = 600_000L,
            distanceKm = 10.0,
            startSocPercent = 80f,
            endSocPercent = 78f,
            consumedKwh = 1.5,
            regeneratedKwh = 0.0,
            distanceAvailable = true,
        )
    )

    @Test fun `a trip is filed, read back and made unknown again`() {
        val store = store()
        assertTrue(store.assign(1L, " Alice "))
        assertEquals(mapOf(1L to "Alice"), store.read())
        assertTrue(store.assign(1L, null))
        assertEquals(emptyMap<Long, String>(), store.read())
    }

    @Test fun `an invalid name is refused and changes nothing`() {
        val store = store()
        store.assign(1L, "Alice")
        assertFalse(store.assign(1L, "  "))
        assertEquals("Alice", store.read()[1L])
    }

    @Test fun `forgetting a driver makes only their trips unknown`() {
        val store = store()
        store.assign(1L, "Alice")
        store.assign(2L, "Bob")
        store.assign(3L, "Alice")
        assertTrue(store.forget("Alice"))
        assertEquals(mapOf(2L to "Bob"), store.read())
    }

    @Test fun `no file and a damaged line read as no driver`() {
        assertEquals(emptyMap<Long, String>(), store().read())
        folder.root.resolve(TripDriverStore.FILE_NAME).writeText("junk\n5\tBob\nx\tAlice\n")
        assertEquals(mapOf(5L to "Bob"), store().read())
    }

    @Test fun `a new trip takes the published driver, or none`() {
        assertEquals("Alice", DriverAttribution.after(existing = null, joined = false, legDriver = "Alice"))
        assertNull(DriverAttribution.after(existing = null, joined = false, legDriver = null))
    }

    @Test fun `a joined leg keeps the first leg's driver unless it contradicts it`() {
        assertEquals("Alice", DriverAttribution.after("Alice", joined = true, legDriver = null))
        assertEquals("Alice", DriverAttribution.after("Alice", joined = true, legDriver = "Alice"))
        assertNull(DriverAttribution.after("Alice", joined = true, legDriver = "Bob"))
        assertNull(DriverAttribution.after(null, joined = true, legDriver = "Bob"))
    }

    @Test fun `attributeSaved files under the stored key`() {
        val store = store()
        store.attributeSaved(10L, joined = false, driver = "Alice")
        store.attributeSaved(10L, joined = true, driver = "Bob")
        assertNull(store.read()[10L])
    }

    @Test fun `the filter matches all, one driver, or the unclaimed trips`() {
        val drivers = mapOf(1L to "Alice")
        assertTrue(DriverAttribution.matches(drivers, 2L, null))
        assertTrue(DriverAttribution.matches(drivers, 1L, "Alice"))
        assertFalse(DriverAttribution.matches(drivers, 2L, "Alice"))
        assertTrue(DriverAttribution.matches(drivers, 2L, DriverAttribution.UNKNOWN))
        assertFalse(DriverAttribution.matches(drivers, 1L, DriverAttribution.UNKNOWN))
    }

    @Test fun `stats list each known driver, then the unclaimed trips`() {
        val trips = listOf(trip(3L), trip(2L), trip(1L))
        val stats = DriverStats.of(trips, mapOf(1L to "Bob", 2L to "Alice", 3L to "Alice"), emptyMap())
        assertEquals(listOf("Alice", "Bob"), stats.map { it.driver })
        assertEquals(2, stats[0].totals.trips)
        assertEquals(20.0, stats[0].totals.distanceKm!!, 1e-9)

        val withUnknown = DriverStats.of(trips, mapOf(1L to "Bob"), emptyMap())
        assertEquals(listOf("Bob", DriverAttribution.UNKNOWN), withUnknown.map { it.driver })
        assertEquals(2, withUnknown[1].totals.trips)
    }

    @Test fun `no stats while nobody is known`() {
        assertTrue(DriverStats.of(listOf(trip(1L)), emptyMap(), emptyMap()).isEmpty())
    }
}
