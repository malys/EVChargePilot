package com.evsuite.chargepilot

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuidanceProbeArtifactTest {

    @Test
    fun `a saved trace leaves the car inside the diagnostic USB bundle`() {
        val filesDir = Files.createTempDirectory("nav-probe-files").toFile()
        val usb = Files.createTempDirectory("nav-probe-usb").toFile()
        try {
            val evidence = filesDir.resolve("evidence")
            val saved = EvidenceCaptureFileStore(evidence) { 1_700_000_000_000L }
                .write(artifact().toJson(), NavGuidanceProbeArtifact.KIND, "SWI69")

            val bundle = DiagnosticExporter.writeBundle("report", evidence, usb, 0L)

            assertNotNull(saved)
            assertNotNull(bundle)
            ZipFile(bundle).use { zip ->
                val entry = zip.entries().toList().single { it.name.startsWith("evidence/") }
                assertEquals("evidence/${saved!!.name}", entry.name)
                val json = JsonParser.parseString(
                    zip.getInputStream(entry).reader().use { it.readText() }
                ).asJsonObject
                assertEquals(NavGuidanceProbeArtifact.PROBE, json["probe"].asString)
                assertEquals(2, json["trace"].asJsonArray.size())
                assertEquals(1, json["undecodedCodes"].asJsonArray.size())
                assertEquals(999, json["undecodedCodes"].asJsonArray[0].asInt)
            }
        } finally {
            filesDir.deleteRecursively()
            usb.deleteRecursively()
        }
    }

    /** A full ring of the widest lines the probe can produce must still fit the bundle. */
    @Test
    fun `a full trace stays under the per-file evidence ceiling`() {
        val line = "%5ds n=%-4d status=%-4s dist=%-8s min=%-6s turn=%-8s road=%s".format(
            10_800, 999_999, "9999", "99999999", "999999", "99999999", "é".repeat(40),
        )
        val json = artifact(trace = List(300) { line }, traceComplete = false).toJson()

        assertTrue(
            json.toByteArray(Charsets.UTF_8).size < DiagnosticExporter.MAX_EVIDENCE_BYTES
        )
    }

    private fun artifact(
        trace: List<String> = listOf("    1s n=1 road=A9", "    2s n=2 road=A9"),
        traceComplete: Boolean = true,
    ) = NavGuidanceProbeArtifact.of(
        savedAtMs = 1_700_000_000_000L,
        firmware = "SWI69",
        adapterBound = true,
        listenerRegistered = true,
        callbacks = trace.size,
        census = mapOf(1 to 4, 999 to 2),
        censusBeyondCeiling = 0,
        trace = trace,
        traceComplete = traceComplete,
    )
}

class NavGuidanceProbeArtifactBoundTest {

    private fun artifact(lines: Int, road: String) = NavGuidanceProbeArtifact.of(
        savedAtMs = 1_700_000_000_000L,
        firmware = "SWI68",
        adapterBound = true,
        listenerRegistered = true,
        callbacks = lines,
        census = mapOf(13 to lines),
        censusBeyondCeiling = 0,
        trace = List(lines) { "%5ds n=%-4d dist=%-8s road=%s".format(it, it, it * 10, road) },
        traceComplete = true,
    )

    @Test fun `a full trace of accented road names still fits the export ceiling`() {
        val json = artifact(300, "Avenue des Carabènes, Saint-Orens-de-Gam")
            .toBoundedJson()
        assertTrue(
            "artifact must stay inside the exporter's per-file limit",
            json.toByteArray(Charsets.UTF_8).size <= NavGuidanceProbeArtifact.MAX_ARTIFACT_BYTES,
        )
    }

    @Test fun `a trace that has to be trimmed says so and keeps the end of the drive`() {
        val json = artifact(300, "x".repeat(40)).toBoundedJson(maxBytes = 4_096)
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= 4_096)
        assertTrue("trimming must mark the trace incomplete", json.contains("\"traceComplete\":false"))
        // The last line of the drive is the one a forecast is judged on; it must survive.
        assertTrue(json.contains("  299s"))
    }

    @Test fun `a short trace is left exactly as it was`() {
        val full = artifact(5, "A61")
        assertEquals(full.toJson(), full.toBoundedJson())
    }
}
