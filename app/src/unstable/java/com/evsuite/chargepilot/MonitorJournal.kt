package com.evsuite.chargepilot

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The trip monitor's life across processes, read at export as validation question Q11 (CP-080).
 *
 * The validation rings are in memory and an export happens in a later process than the drive it
 * is about, so "was the monitor alive when the car moved" could not be read from any bundle. This
 * is the one persistent ring: process start, boot, monitor start/suspension/stop, trip end, ledger
 * write and screen reloads, each stamped with the wall clock, the uptime and what the car last
 * read. An odometer step between two lines with no trip line in between is a missed drive, and a
 * small uptime on the `process start` before it says boot rather than resume or kill.
 *
 * The same atomic write as [AdviceJournal]. An unreadable file is set aside rather than
 * overwritten.
 */
internal class MonitorJournal(
    private val target: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val gson: Gson = Gson(),
) {
    data class Entry(
        val wallMs: Long,
        val elapsedRealtimeMs: Long,
        /** Null until the service has read the car in this process. */
        val odometerKm: Float?,
        val socPercent: Float?,
        val event: String,
    ) {
        fun line(): String = String.format(
            Locale.ROOT,
            "%d %d odo=%s soc=%s %s",
            wallMs,
            elapsedRealtimeMs,
            odometerKm?.let { String.format(Locale.ROOT, "%.1f", it) },
            socPercent?.let { String.format(Locale.ROOT, "%.1f", it) },
            event,
        )
    }

    @Synchronized
    fun read(): List<Entry> {
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        val envelope = runCatching { gson.fromJson(text, Envelope::class.java) }.getOrNull()
        if (envelope == null || envelope.schemaVersion != SCHEMA_VERSION) {
            target.renameTo(File(target.parentFile, "${target.name}.quarantine.${System.currentTimeMillis()}"))
            return emptyList()
        }
        @Suppress("SENSELESS_COMPARISON")
        return envelope.entries.orEmpty().filter { it != null && it.event != null }
    }

    @Synchronized
    fun append(entry: Entry): Boolean = write((read() + entry).takeLast(maxEntries))

    private fun write(entries: List<Entry>): Boolean {
        val bytes = gson.toJson(Envelope(SCHEMA_VERSION, entries)).toByteArray(Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private data class Envelope(val schemaVersion: Int = 0, val entries: List<Entry>? = null)

    companion object {
        const val SCHEMA_VERSION = 1
        /** A few days of ordinary use; ledger lines, one per point of charge, dominate. */
        const val MAX_ENTRIES = 200
        const val FILE_NAME = "monitor-journal.json"

        fun of(filesDir: File) = MonitorJournal(File(filesDir, FILE_NAME))
    }
}
