package com.evsuite.chargepilot.route

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingConfigImportTest {

    private fun directory(): File = Files.createTempDirectory("routing-import").toFile()

    private fun write(directory: File, name: String, text: String): File =
        File(directory, name).apply { writeText(text) }

    @Test
    fun `the config is found by parsing, not by its name`() {
        val stick = directory()
        write(stick, "readme.txt", "this is not a config")
        write(stick, "whatever.dat", "ors_api_key = 5b3ce35abc")

        val found = RoutingConfigImport.search(listOf(stick))
        assertEquals("whatever.dat", found?.file?.name)
        assertEquals("5b3ce35abc", found?.config?.apiKey)
    }

    @Test
    fun `directories are searched in the order they were offered`() {
        val first = directory()
        val second = directory()
        write(first, "config.txt", "ors_api_key = from-the-stick")
        write(second, "config.txt", "ors_api_key = from-internal-storage")

        assertEquals(
            "from-the-stick",
            RoutingConfigImport.search(listOf(first, second))?.config?.apiKey,
        )
    }

    @Test
    fun `a directory with nothing usable in it yields nothing`() {
        val empty = directory()
        write(empty, "photo.jpg", "not a config either")
        assertNull(RoutingConfigImport.search(listOf(empty)))
        assertNull(RoutingConfigImport.search(listOf(File(empty, "does-not-exist"))))
        assertNull(RoutingConfigImport.search(emptyList()))
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
        assertNull(RoutingConfigImport.search(listOf(stick)))
    }

    /**
     * A driver drops the file in whatever folder their file manager made, and the import that
     * answers "nothing here" on a stick that plainly has the key is the bug this fixes.
     */
    @Test
    fun `a config one folder inside the stick is still found`() {
        val stick = directory()
        val folder = File(stick, "Download").apply { mkdirs() }
        write(folder, "ors.txt", "ors_api_key = one-level-down")

        assertEquals(
            "one-level-down",
            RoutingConfigImport.search(listOf(stick))?.config?.apiKey,
        )
    }

    @Test
    fun `the top level wins over a folder, and two levels down is not searched`() {
        val stick = directory()
        val folder = File(stick, "Download").apply { mkdirs() }
        write(folder, "ors.txt", "ors_api_key = one-level-down")
        write(stick, "config.txt", "ors_api_key = at-the-top")
        assertEquals("at-the-top", RoutingConfigImport.search(listOf(stick))?.config?.apiKey)

        val deep = directory()
        val buried = File(deep, "music/albums").apply { mkdirs() }
        write(buried, "ors.txt", "ors_api_key = too-deep")
        assertNull(RoutingConfigImport.search(listOf(deep)))
    }

    @Test
    fun `a config read directly is capped rather than truncated into nonsense`() {
        val stick = directory()
        val file = write(stick, "config.txt", "ors_api_key = abc\nors_base_url = https://ors.example.org")
        val config = RoutingConfigImport.read(file)
        assertEquals("abc", config.apiKey)
        assertEquals("https://ors.example.org", config.baseUrl)
        assertEquals(RoutingConfig(), RoutingConfigImport.read(File(stick, "absent.txt")))
    }
}
