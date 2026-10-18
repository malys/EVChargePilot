package com.evsuite.chargepilot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHygieneTest {

    private val now = 1_792_323_000_000L

    @Test fun `keeps the newest quarantined copies and the live files, drops the rest`() {
        val dir = Files.createTempDirectory("storage-hygiene").toFile()
        val ledger = File(dir, "battery-ledger.json").apply { writeText("{}") }
        (1..8).forEach { File(dir, "battery-ledger.json.quarantine.$it").apply {
            writeText("x"); setLastModified(now - it * 1_000L)
        } }
        File(dir, "advice-journal.json.quarantine.1").writeText("x")
        val bigOld = File(dir, "trips.json.quarantine.1").apply {
            writeText("x".repeat(5_000)); setLastModified(now - 99_000L)
        }
        (2..6).forEach { File(dir, "trips.json.quarantine.$it").apply {
            writeText("x"); setLastModified(now - it * 1_000L)
        } }
        val staleTmp = File(dir, "advice-journal.json.574049888517.tmp").apply {
            writeText("x"); setLastModified(now - 2 * StorageHygiene.STALE_TMP_MS)
        }
        val liveTmp = File(dir, "battery-ledger.json.42.tmp").apply {
            writeText("x"); setLastModified(now)
        }

        assertEquals(4, StorageHygiene.sweep(dir, now))

        val left = dir.list()!!.toSet()
        assertEquals((1..5).map { "battery-ledger.json.quarantine.$it" }.toSet(),
            left.filter { it.startsWith("battery-ledger.json.quarantine.") }.toSet())
        assertTrue("advice-journal.json.quarantine.1" in left)
        assertTrue(ledger.exists() && liveTmp.exists() && !staleTmp.exists())
        assertTrue("a large copy may be a history worth recovering", bigOld.exists())
    }

    @Test fun `does nothing on the factory clock`() {
        val dir = Files.createTempDirectory("storage-hygiene").toFile()
        File(dir, "a.json.1.tmp").apply { writeText("x"); setLastModified(0L) }
        assertEquals(0, StorageHygiene.sweep(dir, 1_546_300_835_000L))
    }
}
