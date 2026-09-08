package com.evsuite.chargepilot.route

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationFavoritesImportTest {

    private fun directory(): File = Files.createTempDirectory("destinations-import").toFile()

    private fun write(directory: File, name: String, text: String): File =
        File(directory, name).apply { writeText(text) }

    @Test
    fun `the list is read by parsing, not by the file's name`() {
        val stick = directory()
        val favorites = DestinationFavoritesImport.read(
            write(stick, "whatever.dat", "Home = 5.4474,43.5297")
        )
        assertEquals(listOf(OrsGeocode.Place("Home", 5.4474, 43.5297)), favorites)
    }

    @Test
    fun `a line that is not label equals longitude,latitude is skipped, not the whole file`() {
        val stick = directory()
        val text = "Home = 5.4474,43.5297\nnot a line\nOffice = 3.8767,43.6108"
        assertEquals(2, DestinationFavoritesImport.read(write(stick, "list.txt", text)).size)
    }

    @Test
    fun `a file that holds no favourite reads as an empty list`() {
        val stick = directory()
        assertTrue(DestinationFavoritesImport.read(write(stick, "photo.jpg", "not a list")).isEmpty())
        assertTrue(DestinationFavoritesImport.read(File(stick, "does-not-exist")).isEmpty())
        assertTrue(DestinationFavoritesImport.read(stick).isEmpty())
    }

    @Test
    fun `a file too large to be a favourites list is never read`() {
        val stick = directory()
        val oversized = write(
            stick,
            "big.bin",
            "Home = 5.4474,43.5297\n" + "x".repeat(DestinationFavoritesImport.MAX_FILE_BYTES),
        )
        assertTrue(oversized.length() > DestinationFavoritesImport.MAX_FILE_BYTES)
        assertTrue(DestinationFavoritesImport.read(oversized).isEmpty())
    }

    @Test
    fun `a coordinate that is not a point on Earth is skipped, whatever it parses as`() {
        val text = listOf(
            "Nowhere = NaN,43.5297",
            "Elsewhere = 5.4474,Infinity",
            "Off the map = 999,43.5297",
            "Off the pole = 5.4474,91",
            "Home = 5.4474,43.5297",
        ).joinToString("\n")

        // Every one of the four parses as a Double, and none of them is a place.
        assertEquals(
            listOf(OrsGeocode.Place("Home", 5.4474, 43.5297)),
            DestinationFavoritesImport.parse(text),
        )
    }

    @Test
    fun `more lines than the favourites cap are truncated, not refused`() {
        val stick = directory()
        val text = (1..DestinationFavorites.MAX_FAVORITES + 10)
            .joinToString("\n") { "Place $it = 1.0,1.0" }
        assertEquals(
            DestinationFavorites.MAX_FAVORITES,
            DestinationFavoritesImport.read(write(stick, "list.txt", text)).size,
        )
    }
}
