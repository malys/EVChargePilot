package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AdviceJournalTest {

    @Test
    fun `entries round-trip in order, bounded, and keep their notified mark`() {
        val dir = Files.createTempDirectory("advice").toFile()
        val journal = AdviceJournal(File(dir, AdviceJournal.FILE_NAME), maxEntries = 2)
        val first = AdviceEntry(1L, AdviceKind.TRIP_END, "one")
        val second = AdviceEntry(2L, AdviceKind.BATTERY, "two")
        journal.append(first)
        journal.append(second)
        journal.append(AdviceEntry(3L, AdviceKind.BATTERY, "three"))
        assertEquals(listOf("two", "three"), journal.read().map { it.text })
        journal.markNotified(listOf(second))
        assertEquals(listOf(true, false), journal.read().map { it.notified })
        dir.deleteRecursively()
    }

    @Test
    fun `an unreadable file is set aside, not overwritten`() {
        val dir = Files.createTempDirectory("advice").toFile()
        val file = File(dir, AdviceJournal.FILE_NAME).apply { writeText("{not json") }
        assertTrue(AdviceJournal(file).read().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.contains("quarantine") })
        dir.deleteRecursively()
    }
}
