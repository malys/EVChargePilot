package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.TelemetryEvidenceFormat
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCaptureFileStoreTest {

    @Test
    fun `a new capture never overwrites an earlier file with the same timestamp`() {
        val directory = Files.createTempDirectory("evidence-store").toFile()
        try {
            val store = EvidenceCaptureFileStore(directory) { 1_700_000_000_000L }
            val first = store.write(capture(snapshots = 1))
            val firstContents = first?.readText()
            val second = store.write(capture(snapshots = 2))

            assertNotNull(first)
            assertNotNull(second)
            assertNotEquals(first, second)
            assertEquals(firstContents, first?.readText())
            assertEquals(2, TelemetryEvidenceFormat.fromJson(second!!.readText())?.snapshots)
            assertEquals(2, directory.listFiles { file -> file.extension == "json" }?.size)
            assertTrue(directory.listFiles { file -> file.extension == "tmp" }.isNullOrEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a non-directory target fails without touching its contents`() {
        val parent = Files.createTempDirectory("evidence-store-failure").toFile()
        val occupied = parent.resolve("evidence").apply { writeText("previous") }
        try {
            val result = EvidenceCaptureFileStore(occupied).write(capture(snapshots = 1))

            assertEquals(null, result)
            assertEquals("previous", occupied.readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `repeated captures retain only the newest bounded set`() {
        val directory = Files.createTempDirectory("evidence-store-retention").toFile()
        try {
            val store = EvidenceCaptureFileStore(directory) { 1_700_000_000_000L }
            repeat(EvidenceCaptureFileStore.MAX_CAPTURE_FILES + 2) { index ->
                assertNotNull(store.write(capture(snapshots = index + 1)))
            }

            val retainedSnapshots = directory.listFiles { file -> file.extension == "json" }
                .orEmpty()
                .mapNotNull { TelemetryEvidenceFormat.fromJson(it.readText())?.snapshots }
                .toSet()
            assertEquals(EvidenceCaptureFileStore.MAX_CAPTURE_FILES, retainedSnapshots.size)
            // The two oldest of the writes above are evicted; the rest survive, whatever the
            // pool size happens to be.
            val newest = EvidenceCaptureFileStore.MAX_CAPTURE_FILES + 2
            assertEquals((3..newest).toSet(), retainedSnapshots)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a write removes stale temporary evidence files`() {
        val directory = Files.createTempDirectory("evidence-store-temp").toFile()
        val stale = directory.resolve(".orphan.tmp").apply { writeText("partial") }
        try {
            assertNotNull(EvidenceCaptureFileStore(directory).write(capture(snapshots = 1)))

            assertFalse(stale.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `capture filename carries the selected real vehicle scenario`() {
        val root = Files.createTempDirectory("evidence-store-scenario").toFile()
        val directory = root.resolve("evidence")
        try {
            assertTrue(
                VehicleTestContextStore(root).write(
                    VehicleTestContextStore.Scenario.STATIONARY_HVAC_MAX,
                    nowMs = 123L,
                )
            )

            val file = EvidenceCaptureFileStore(directory).write(capture(snapshots = 1))

            assertNotNull(file)
            assertTrue(file!!.name.contains("-stationary-hvac-max-"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun capture(snapshots: Int) = EvidenceCapture(
        firmware = "SWI68",
        startedAtMs = 1L,
        endedAtMs = 2L,
        snapshots = snapshots,
        signals = emptyList(),
    )
}
