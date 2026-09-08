package com.evsuite.chargepilot.route

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationFavoritesExportTest {

    private fun stick(): File = Files.createTempDirectory("destinations-export").toFile()

    @Test
    fun `an exported file is a file this app can import back`() {
        val favorites = listOf(
            OrsGeocode.Place("Home", 5.4474, 43.5297),
            OrsGeocode.Place("Office, 12 Rue de la Paix", 3.8767, 43.6108),
        )
        val directory = stick()

        val written = DestinationFavoritesExport.write(directory, favorites)

        assertEquals(DestinationFavoritesExport.FILE_NAME, written?.name)
        assertEquals(
            favorites,
            DestinationFavoritesImport.read(File(directory, DestinationFavoritesExport.FILE_NAME)),
        )
    }

    @Test
    fun `a label carrying its own comma still parses back, because the separator is the equals sign`() {
        val text = DestinationFavoritesExport.format(
            listOf(OrsGeocode.Place("Office, 12 Rue de la Paix", 3.8767, 43.6108))
        )

        assertEquals("Office, 12 Rue de la Paix", DestinationFavoritesImport.parse(text).single().label)
    }

    @Test
    fun `a second export replaces the first, so an import cannot pick a stale list`() {
        val directory = stick()
        DestinationFavoritesExport.write(directory, listOf(OrsGeocode.Place("Old", 1.0, 1.0)))

        DestinationFavoritesExport.write(directory, listOf(OrsGeocode.Place("New", 2.0, 2.0)))

        assertEquals(1, directory.listFiles()?.size)
        assertEquals(
            listOf(OrsGeocode.Place("New", 2.0, 2.0)),
            DestinationFavoritesImport.read(File(directory, DestinationFavoritesExport.FILE_NAME)),
        )
    }

    @Test
    fun `an empty list writes no file at all`() {
        val directory = stick()

        assertNull(DestinationFavoritesExport.write(directory, emptyList()))

        assertEquals(0, directory.listFiles()?.size)
    }
}
