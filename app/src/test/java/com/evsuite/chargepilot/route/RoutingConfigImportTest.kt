package com.evsuite.chargepilot.route

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingConfigImportTest {

    private fun directory(): File = Files.createTempDirectory("routing-import").toFile()

    private fun write(directory: File, name: String, text: String): File =
        File(directory, name).apply { writeText(text) }

    @Test
    fun `the config is read by parsing, not by its name`() {
        val stick = directory()
        val config = RoutingConfigImport.read(write(stick, "whatever.dat", "ors_api_key = 5b3ce35abc"))
        assertEquals("5b3ce35abc", config.apiKey)
    }

    @Test
    fun `a file that is not a config reads as empty`() {
        val stick = directory()
        assert(RoutingConfigImport.read(write(stick, "photo.jpg", "not a config")).isEmpty())
        assert(RoutingConfigImport.read(File(stick, "does-not-exist")).isEmpty())
        assert(RoutingConfigImport.read(stick).isEmpty())
    }

    @Test
    fun `a file too large to be a config is never read`() {
        val stick = directory()
        val oversized = write(
            stick,
            "big.bin",
            "ors_api_key = ignored\n" + "x".repeat(RoutingConfig.MAX_FILE_BYTES),
        )
        assert(oversized.length() > RoutingConfig.MAX_FILE_BYTES)
        // Capped reading alone would have parsed the key out of the first bytes.
        assert(RoutingConfigImport.read(oversized).isEmpty())
    }

    @Test
    fun `a config read directly is capped rather than truncated into nonsense`() {
        val stick = directory()
        val file = write(stick, "config.txt", "ors_api_key = abc\nors_base_url = https://ors.example.org")
        val config = RoutingConfigImport.read(file)
        assertEquals("abc", config.apiKey)
        assertEquals("https://ors.example.org", config.baseUrl)
    }
}
