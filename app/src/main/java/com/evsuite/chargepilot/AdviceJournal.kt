package com.evsuite.chargepilot

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/** Where an advice came from. The history screen and the notification cap read it. */
enum class AdviceKind { TRIP_END, BATTERY, PARKED, CHARGING, DRIVING }

/**
 * One advice as the driver was given it.
 *
 * The text is kept as it was said, in the language it was said in: it is a record of what the app
 * told the driver, and a history that rewrote itself would not be one.
 */
data class AdviceEntry(
    val atMs: Long,
    val kind: AdviceKind,
    val text: String,
    val notified: Boolean = false,
)

/**
 * Every advice the app gives, newest last, bounded (CP-087).
 *
 * The same atomic write as the trip and ledger stores. An unreadable file is set aside rather
 * than overwritten, so a bad write never loses the history that was there.
 */
class AdviceJournal(
    private val target: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val gson: Gson = Gson(),
) {
    @Synchronized
    fun read(): List<AdviceEntry> {
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        val envelope = runCatching { gson.fromJson(text, Envelope::class.java) }.getOrNull()
        if (envelope == null || envelope.schemaVersion != SCHEMA_VERSION) {
            target.renameTo(File(target.parentFile, "${target.name}.quarantine.${System.currentTimeMillis()}"))
            return emptyList()
        }
        @Suppress("SENSELESS_COMPARISON")
        return envelope.entries.orEmpty()
            .filter { it != null && it.kind != null && it.text != null }
            .sortedBy { it.atMs }
    }

    @Synchronized
    fun append(entry: AdviceEntry): Boolean = write((read() + entry).takeLast(maxEntries))

    /** Marks the given entries as notified; the rest stay as they are. */
    @Synchronized
    fun markNotified(entries: Collection<AdviceEntry>): Boolean {
        val marked = entries.toSet()
        return write(read().map { if (it in marked) it.copy(notified = true) else it })
    }

    private fun write(entries: List<AdviceEntry>): Boolean {
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

    private data class Envelope(val schemaVersion: Int = 0, val entries: List<AdviceEntry>? = null)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 500
        const val FILE_NAME = "advice-journal.json"

        fun of(filesDir: File) = AdviceJournal(File(filesDir, FILE_NAME))
    }
}
