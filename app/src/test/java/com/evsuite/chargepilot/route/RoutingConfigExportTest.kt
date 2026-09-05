package com.evsuite.chargepilot.route

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingConfigExportTest {

    private fun stick(): File = Files.createTempDirectory("routing-export").toFile()

    @Test
    fun `an exported file is a file this app can import back`() {
        val config = RoutingConfig(
            apiKey = "5b3ce3597851110001cf6248abc",
            baseUrl = "https://ors.example.org",
            chargerApiKey = "0a1b2c3d",
            chargerBaseUrl = "https://ocm.example.org",
        )
        val directory = stick()

        val written = RoutingConfigExport.write(directory, config)

        assertEquals(RoutingConfigExport.FILE_NAME, written?.name)
        val found = RoutingConfigImport.search(listOf(directory))
        assertEquals(config, found?.config)
    }

    @Test
    fun `values that were never set stay out, so importing back blanks nothing`() {
        val text = RoutingConfigExport.format(RoutingConfig(apiKey = "abc"))

        assertEquals("abc", RoutingConfig.parse(text).apiKey)
        assertNull(RoutingConfig.parse(text).baseUrl)
        assertNull(RoutingConfig.parse(text).chargerApiKey)
    }

    @Test
    fun `a second export replaces the first, so an import cannot pick a stale key`() {
        val directory = stick()
        RoutingConfigExport.write(directory, RoutingConfig(apiKey = "old"))

        RoutingConfigExport.write(directory, RoutingConfig(apiKey = "new"))

        assertEquals(1, directory.listFiles()?.size)
        assertEquals("new", RoutingConfigImport.search(listOf(directory))?.config?.apiKey)
    }

    @Test
    fun `an empty configuration writes no file at all`() {
        val directory = stick()

        assertNull(RoutingConfigExport.write(directory, RoutingConfig()))

        assertEquals(0, directory.listFiles()?.size)
    }
}
