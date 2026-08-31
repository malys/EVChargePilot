package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.TelemetryEvidenceFormat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a completed capture atomically and retains only a small fixed set. */
internal class EvidenceCaptureFileStore(
    private val directory: File,
    private val maxFiles: Int = MAX_CAPTURE_FILES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun write(capture: EvidenceCapture): File? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (maxFiles <= 0 || !prepareDirectory()) return null
        val target = uniqueTarget(capture)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(TelemetryEvidenceFormat.toJson(capture).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (temp.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun uniqueTarget(capture: EvidenceCapture): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(nowMs()))
        val firmware = capture.firmware.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = "evidence-$firmware-$stamp"
        var candidate = File(directory, "$base.json")
        var suffix = 1
        while (candidate.exists()) candidate = File(directory, "$base-${suffix++}.json")
        return candidate
    }

    private fun prepareDirectory(): Boolean {
        val files = directory.listFiles() ?: return false
        val staleTemps = files.filter {
            it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp")
        }
        if (!staleTemps.all(File::delete)) return false
        val oldCaptures = files
            .filter { it.isFile && it.extension == "json" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy(::captureOrder))
        val excess = (oldCaptures.size - maxFiles + 1).coerceAtLeast(0)
        return oldCaptures.take(excess).all(File::delete)
    }

    private fun captureOrder(file: File): String {
        val match = CAPTURE_NAME.matchEntire(file.nameWithoutExtension) ?: return file.name
        val collision = match.groupValues[2].ifEmpty { "0" }.padStart(10, '0')
        return "${match.groupValues[1]}-$collision"
    }

    companion object {
        internal const val MAX_CAPTURE_FILES = 8
        private val CAPTURE_NAME = Regex("^(.*-\\d{8}-\\d{6}-\\d{3})(?:-(\\d+))?$")
    }
}
