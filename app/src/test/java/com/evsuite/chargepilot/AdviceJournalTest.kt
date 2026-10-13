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
    fun `a file written before notifiedAtMs reads, and a posted entry is stamped`() {
        val dir = Files.createTempDirectory("advice").toFile()
        val file = File(dir, AdviceJournal.FILE_NAME).apply {
            writeText(
                """{"schemaVersion":1,"entries":[""" +
                    """{"atMs":1,"kind":"BATTERY","text":"held","notified":false},""" +
                    """{"atMs":2,"kind":"BATTERY","text":"posted","notified":false}]}"""
            )
        }
        val journal = AdviceJournal(file)
        val old = journal.read()
        assertEquals(listOf(null, null), old.map { it.notifiedAtMs })
        journal.markNotified(old, posted = listOf(old[1]), nowMs = 99L)
        val marked = journal.read()
        assertEquals(listOf(true, true), marked.map { it.notified })
        assertEquals(listOf(null, 99L), marked.map { it.notifiedAtMs })
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
