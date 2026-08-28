package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.TelemetryEvidenceFormat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a completed capture without ever removing or overwriting an earlier capture. */
internal class EvidenceCaptureFileStore(
    private val directory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun write(capture: EvidenceCapture): File? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
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
}
