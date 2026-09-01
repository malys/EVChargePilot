package com.evsuite.chargepilot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes one bounded diagnostic report to an explicitly selected removable volume. */
object DiagnosticExporter {

    internal const val MAX_REPORT_BYTES = 128 * 1024
    internal const val MAX_EVIDENCE_FILES = 8
    internal const val MAX_EVIDENCE_BYTES = 64 * 1024
    internal const val MAX_BUNDLE_BYTES = 768 * 1024
    internal const val TRUNCATION_MARKER = "\n[report truncated at 128 KiB]\n"

    fun export(context: Context, report: String, chosenDirectory: File): File? {
        val target = DiagnosticUsbStorage.writableTarget(context, chosenDirectory) ?: return null
        return writeBundle(report, File(context.filesDir, "evidence"), target)
    }

    /**
     * One self-contained USB artifact: readable report plus newest bounded CP-003 captures.
     * Nothing is copied back to internal storage, and malformed/oversized capture files are
     * listed as skipped rather than allowed to grow the bundle without limit.
     */
    internal fun writeBundle(
        report: String,
        evidenceDirectory: File,
        directory: File,
        nowMs: Long = System.currentTimeMillis(),
    ): File? {
        if (!directory.isDirectory) return null
        val target = uniqueTarget(directory, fileStem(nowMs), "zip") ?: return null
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            val reportBytes = bounded(report).toByteArray(Charsets.UTF_8)
            val evidence = evidenceFiles(evidenceDirectory)
            val manifest = manifest(nowMs, reportBytes, evidence)
            FileOutputStream(temp).use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    zip.writeEntry("manifest.txt", manifest.toByteArray(Charsets.UTF_8), nowMs)
                    zip.writeEntry("diagnostic.txt", reportBytes, nowMs)
                    evidence.included.forEach { artifact ->
                        zip.writeEntry("evidence/${artifact.file.name}", artifact.bytes, artifact.modifiedMs)
                    }
                }
            }
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            if (temp.length() > MAX_BUNDLE_BYTES || !temp.renameTo(target)) null else target
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    internal fun write(report: String, directory: File, nowMs: Long = System.currentTimeMillis()): File? {
        if (!directory.isDirectory) return null
        val target = uniqueTarget(directory, fileStem(nowMs), "txt") ?: return null
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

    private fun uniqueTarget(directory: File, stem: String, extension: String): File? {
        repeat(MAX_SAME_SECOND_EXPORTS) { suffix ->
            val name = if (suffix == 0) "$stem.$extension" else "$stem-$suffix.$extension"
            File(directory, name).takeUnless(File::exists)?.let { return it }
        }
        return null
    }

    private fun evidenceFiles(directory: File): EvidenceSelection {
        val candidates = runCatching { directory.listFiles() }
            .getOrNull()
            ?.filter { file ->
                file.isFile && file.extension == "json" && SAFE_FILE_NAME.matches(file.name)
            }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending(File::getName))
            .orEmpty()
            .take(MAX_EVIDENCE_FILES)
        val included = mutableListOf<EvidenceArtifact>()
        val skipped = mutableListOf<String>()
        candidates.forEach { file ->
            if (file.length() !in 1..MAX_EVIDENCE_BYTES.toLong()) {
                skipped += "${file.name}: size=${file.length()}"
                return@forEach
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.size !in 1..MAX_EVIDENCE_BYTES) {
                skipped += "${file.name}: unreadable-or-size-changed"
            } else {
                included += EvidenceArtifact(file, bytes, file.lastModified())
            }
        }
        return EvidenceSelection(included, skipped)
    }

    private fun manifest(
        nowMs: Long,
        report: ByteArray,
        evidence: EvidenceSelection,
    ): String = buildString {
        appendLine("schema=1")
        appendLine("created_epoch_ms=$nowMs")
        appendLine("bundle_limit_bytes=$MAX_BUNDLE_BYTES")
        appendLine("diagnostic_bytes=${report.size}")
        appendLine("diagnostic_sha256=${sha256(report)}")
        appendLine("evidence_included=${evidence.included.size}")
        appendLine("evidence_skipped=${evidence.skipped.size}")
        evidence.included.forEachIndexed { index, artifact ->
            appendLine("evidence.$index.name=${artifact.file.name}")
            appendLine("evidence.$index.bytes=${artifact.bytes.size}")
            appendLine("evidence.$index.modified_epoch_ms=${artifact.modifiedMs}")
            appendLine("evidence.$index.sha256=${sha256(artifact.bytes)}")
        }
        evidence.skipped.forEachIndexed { index, reason ->
            appendLine("skipped.$index=$reason")
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray, modifiedMs: Long) {
        putNextEntry(ZipEntry(name).apply { time = modifiedMs.coerceAtLeast(0L) })
        write(bytes)
        closeEntry()
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private const val MAX_SAME_SECOND_EXPORTS = 100
    private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9._-]+\\.json$")

    private data class EvidenceArtifact(
        val file: File,
        val bytes: ByteArray,
        val modifiedMs: Long,
    )

    private data class EvidenceSelection(
        val included: List<EvidenceArtifact>,
        val skipped: List<String>,
    )

    /** Keeps the report head and a visible marker without splitting a UTF-8 sequence. */
    internal fun bounded(report: String): String {
        val bytes = report.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_REPORT_BYTES) return report
        val marker = TRUNCATION_MARKER.toByteArray(Charsets.UTF_8)
        var end = MAX_REPORT_BYTES - marker.size
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + TRUNCATION_MARKER
    }

    /** Keeps newest events for a bounded log section without cutting a UTF-8 sequence. */
    internal fun boundedTail(content: String, maxBytes: Int, marker: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return content
        val markerBytes = marker.toByteArray(Charsets.UTF_8)
        if (markerBytes.size >= maxBytes) return String(markerBytes, 0, maxBytes, Charsets.UTF_8)
        var start = bytes.size - (maxBytes - markerBytes.size)
        while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) start++
        return marker + String(bytes, start, bytes.size - start, Charsets.UTF_8)
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
