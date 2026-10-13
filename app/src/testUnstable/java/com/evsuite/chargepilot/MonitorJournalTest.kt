package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MonitorJournalTest {

    @Test
    fun `entries round-trip in order and stay bounded`() {
        val dir = Files.createTempDirectory("monitor").toFile()
        val journal = MonitorJournal(File(dir, MonitorJournal.FILE_NAME), maxEntries = 2)
        journal.append(MonitorJournal.Entry(1L, 10L, null, null, "process start"))
        journal.append(MonitorJournal.Entry(2L, 20L, 23408.04f, 71.25f, "monitor started"))
        journal.append(MonitorJournal.Entry(3L, 30L, 23412.5f, 70f, "trip end automatic=true"))
        // A fresh instance reads what the last one wrote: the journal outlives the process.
        val read = MonitorJournal.of(dir).read()
        assertEquals(listOf("monitor started", "trip end automatic=true"), read.map { it.event })
        assertEquals("2 20 odo=23408.0 soc=71.3 monitor started", read.first().line())
        dir.deleteRecursively()
    }

    @Test
    fun `a missing reading prints null, never zero`() {
        assertEquals(
            "5 6 odo=null soc=null boot received, detection=on",
            MonitorJournal.Entry(5L, 6L, null, null, "boot received, detection=on").line(),
        )
    }

    @Test
    fun `an unreadable or foreign-schema file is set aside, not overwritten`() {
        val dir = Files.createTempDirectory("monitor").toFile()
        val file = File(dir, MonitorJournal.FILE_NAME).apply { writeText("""{"schemaVersion":9}""") }
        assertTrue(MonitorJournal(file).read().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.contains("quarantine") })
        dir.deleteRecursively()
    }
}
