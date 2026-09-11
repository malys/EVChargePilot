package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Where a diagnostic bundle actually lands when the stick's root will not take it.
 *
 * The fallback is the part worth pinning: FAT permission bits lie, so writability is decided by
 * a create-and-delete probe rather than by `canWrite()`, and the fallback directory must be on
 * **the same volume the driver chose**. Falling back to this app's folder on some other volume
 * would answer "exported" and put the file where they will never look — which is the failure
 * this whole discovery path exists to stop.
 */
class DiagnosticUsbStorageTest {

    /** `getExternalFilesDirs` hands back `<volume>/Android/data/<package>/files`: four levels. */
    private fun appDirectoryOn(volume: File): File =
        File(volume, "Android/data/com.evsuite.chargepilot/files").also { it.mkdirs() }

    private fun tempDirectory(): File = Files.createTempDirectory("chargepilot-usb").toFile()

    /**
     * A directory this process cannot write into. A suite running as root writes into
     * everything, so that case skips rather than passing on an assertion it never made.
     */
    private fun lockedDirectory(parent: File): File {
        val locked = File(parent, "locked").also { it.mkdirs(); it.setWritable(false) }
        assumeFalse("this machine writes into a read-only directory", locked.canWrite())
        return locked
    }

    @Test
    fun `a folder that takes a file is the folder the bundle goes in`() {
        val volume = tempDirectory()
        val chosen = File(volume, "reports").also { it.mkdirs() }

        assertEquals(
            chosen,
            DiagnosticUsbStorage.writableTarget(chosen, listOf(appDirectoryOn(volume))),
        )
    }

    @Test
    fun `a read-only choice falls back to this app's folder on the same volume`() {
        val volume = tempDirectory()
        val appDirectory = appDirectoryOn(volume)
        val chosen = lockedDirectory(volume)

        assertEquals(appDirectory, DiagnosticUsbStorage.writableTarget(chosen, listOf(appDirectory)))

        chosen.setWritable(true)
    }

    @Test
    fun `it never falls back onto a volume the driver did not choose`() {
        val chosenVolume = tempDirectory()
        val otherVolume = tempDirectory()
        val chosen = lockedDirectory(chosenVolume)

        assertNull(DiagnosticUsbStorage.writableTarget(chosen, listOf(appDirectoryOn(otherVolume))))

        chosen.setWritable(true)
    }

    @Test
    fun `a volume this app has no folder on and cannot write to has nowhere to go`() {
        val volume = tempDirectory()
        val chosen = lockedDirectory(volume)

        assertNull(DiagnosticUsbStorage.writableTarget(chosen, emptyList()))

        chosen.setWritable(true)
    }

    @Test
    fun `the browser starts at the volume root when the app may list it`() {
        val volume = tempDirectory()

        assertEquals(volume, DiagnosticUsbStorage.appVisibleRoot(appDirectoryOn(volume)) { true })
    }

    @Test
    fun `an opaque volume root leaves the browser in the folder this app can reach`() {
        // This head unit mounts sticks the framework never hears about: the root of one may be
        // unlistable while `Android/data/<package>/files` on it is perfectly readable.
        val volume = tempDirectory()
        val appDirectory = appDirectoryOn(volume)

        assertEquals(appDirectory, DiagnosticUsbStorage.appVisibleRoot(appDirectory) { false })
    }

    @Test
    fun `a probe file is never left behind on the driver's stick`() {
        val volume = tempDirectory()
        val chosen = File(volume, "reports").also { it.mkdirs() }

        DiagnosticUsbStorage.writableTarget(chosen, listOf(appDirectoryOn(volume)))

        assertTrue(chosen.listFiles()!!.none { it.name.startsWith(".evchargepilot-write-") })
    }
}
