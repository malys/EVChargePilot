package com.evsuite.chargepilot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes one bounded diagnostic report to an explicitly selected removable volume. */
object DiagnosticExporter {

    internal const val MAX_REPORT_BYTES = 128 * 1024
    internal const val TRUNCATION_MARKER = "\n[report truncated at 128 KiB]\n"

    fun export(context: Context, report: String, chosenDirectory: File): File? {
        val target = DiagnosticUsbStorage.writableTarget(context, chosenDirectory) ?: return null
        return write(report, target)
    }

    internal fun write(report: String, directory: File, nowMs: Long = System.currentTimeMillis()): File? {
        if (!directory.isDirectory) return null
        val target = uniqueTarget(directory, fileStem(nowMs)) ?: return null
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            val bytes = bounded(report).toByteArray(Charsets.UTF_8)
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (temp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun fileStem(nowMs: Long): String = "evchargepilot-diagnostic-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))

    private fun uniqueTarget(directory: File, stem: String): File? {
        repeat(MAX_SAME_SECOND_EXPORTS) { suffix ->
            val name = if (suffix == 0) "$stem.txt" else "$stem-$suffix.txt"
            File(directory, name).takeUnless(File::exists)?.let { return it }
        }
        return null
    }

    private const val MAX_SAME_SECOND_EXPORTS = 100

    /** Keeps the report head and a visible marker without splitting a UTF-8 sequence. */
    internal fun bounded(report: String): String {
        val bytes = report.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_REPORT_BYTES) return report
        val marker = TRUNCATION_MARKER.toByteArray(Charsets.UTF_8)
        var end = MAX_REPORT_BYTES - marker.size
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + TRUNCATION_MARKER
    }
}

/** Pure fail-closed parked gate, kept separate so stale-sample behavior is JVM-tested. */
object DiagnosticExportPolicy {
    enum class Decision { ALLOWED, SPEED_UNAVAILABLE, VEHICLE_MOVING }

    fun decide(speedKmh: Float?, sampledAtMs: Long?, nowMs: Long): Decision {
        val ageMs = sampledAtMs?.let { nowMs - it }
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0f ||
            ageMs == null || ageMs !in 0..5_000L
        ) {
            return Decision.SPEED_UNAVAILABLE
        }
        return if (speedKmh <= 0.1f) Decision.ALLOWED else Decision.VEHICLE_MOVING
    }
}
