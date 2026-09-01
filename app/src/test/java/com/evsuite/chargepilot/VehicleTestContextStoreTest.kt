package com.evsuite.chargepilot

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTestContextStoreTest {

    @Test fun `scenario marker is overwrite only and round trips`() {
        val directory = Files.createTempDirectory("vehicle-test-context").toFile()
        try {
            val store = VehicleTestContextStore(directory)

            assertTrue(store.write(VehicleTestContextStore.Scenario.MOTORWAY_110, 100L))
            assertTrue(store.write(VehicleTestContextStore.Scenario.MOTORWAY_130, 200L))

            assertEquals(
                VehicleTestContextStore.Context(
                    VehicleTestContextStore.Scenario.MOTORWAY_130,
                    200L,
                ),
                store.read(),
            )
            assertEquals(1, directory.listFiles()!!.count { !it.name.startsWith(".") })
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
