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
