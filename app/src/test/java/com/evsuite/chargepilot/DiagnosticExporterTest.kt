package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticExporterTest {

    @Test fun `report is complete and no temporary file remains`() {
        val directory = tempDirectory()

        val exported = DiagnosticExporter.write("firmware=SWI68\nstatus=unvalidated\n", directory, 0L)

        assertNotNull(exported)
        assertEquals("firmware=SWI68\nstatus=unvalidated\n", exported!!.readText())
        assertTrue(exported.name.startsWith("evchargepilot-diagnostic-"))
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun `report size is bounded before it reaches storage`() {
        val directory = tempDirectory()
        val oversized = "x".repeat(DiagnosticExporter.MAX_REPORT_BYTES + 10_000)

        val exported = DiagnosticExporter.write(oversized, directory, 0L)

        assertTrue(exported!!.length() <= DiagnosticExporter.MAX_REPORT_BYTES)
        assertTrue(exported.readText().endsWith(DiagnosticExporter.TRUNCATION_MARKER))
    }

    @Test fun `utf8 truncation is valid and visibly disclosed`() {
        val oversized = "é".repeat(DiagnosticExporter.MAX_REPORT_BYTES)

        val bounded = DiagnosticExporter.bounded(oversized)

        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= DiagnosticExporter.MAX_REPORT_BYTES)
        assertTrue(bounded.endsWith(DiagnosticExporter.TRUNCATION_MARKER))
        assertFalse(bounded.contains('\uFFFD'))
    }

    @Test fun `same-second exports never overwrite one another`() {
        val directory = tempDirectory()

        val first = DiagnosticExporter.write("first", directory, 0L)
        val second = DiagnosticExporter.write("second", directory, 0L)

        assertEquals("first", first!!.readText())
        assertEquals("second", second!!.readText())
        assertTrue(first.name != second.name)
    }

    @Test fun `missing destination fails without creating a file`() {
        val missing = File(tempDirectory(), "not-mounted")

        assertNull(DiagnosticExporter.write("report", missing, 0L))
        assertFalse(missing.exists())
    }

    @Test fun `unwritable root can fall back only to its matching app directory`() {
        val volume = tempDirectory()
        val chosenFile = File(volume, "not-a-directory").apply { writeText("occupied") }
        val appDirectory = File(volume, "Android/data/com.evsuite.chargepilot/files")
            .apply { mkdirs() }
        val otherAppDirectory = File(tempDirectory(), "Android/data/other/files")
            .apply { mkdirs() }

        assertEquals(
            appDirectory.canonicalPath,
            DiagnosticUsbStorage.writableTarget(
                chosenFile,
                listOf(otherAppDirectory, appDirectory),
            )?.canonicalPath,
        )
    }

    @Test fun `opaque removable root exposes the app visible folder instead`() {
        val volume = tempDirectory()
        val appDirectory = File(volume, "Android/data/com.evsuite.chargepilot/files")
            .apply { mkdirs() }

        assertEquals(
            volume.canonicalPath,
            DiagnosticUsbStorage.appVisibleRoot(appDirectory) { true }.canonicalPath,
        )
        assertEquals(
            appDirectory.canonicalPath,
            DiagnosticUsbStorage.appVisibleRoot(appDirectory) { false }.canonicalPath,
        )
    }

    @Test fun `diagnostic export gate requires a fresh readable parked speed`() {
        val now = 10_000L

        assertEquals(
            DiagnosticExportPolicy.Decision.ALLOWED,
            DiagnosticExportPolicy.decide(0.1f, now - 5_000L, now),
        )
        assertEquals(
            DiagnosticExportPolicy.Decision.VEHICLE_MOVING,
            DiagnosticExportPolicy.decide(0.2f, now, now),
        )
        listOf(
            DiagnosticExportPolicy.decide(null, now, now),
            DiagnosticExportPolicy.decide(Float.NaN, now, now),
            DiagnosticExportPolicy.decide(-1f, now, now),
            DiagnosticExportPolicy.decide(0f, now - 5_001L, now),
            DiagnosticExportPolicy.decide(0f, now + 1L, now),
        ).forEach {
            assertEquals(DiagnosticExportPolicy.Decision.SPEED_UNAVAILABLE, it)
        }
    }

    private fun tempDirectory(): File = Files.createTempDirectory("diagnostic-export").toFile()
}
