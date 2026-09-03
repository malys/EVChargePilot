package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.TelemetryEvidenceFormat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a completed probe artifact atomically and retains only a small fixed set. */
internal class EvidenceCaptureFileStore(
    private val directory: File,
    private val maxFiles: Int = MAX_CAPTURE_FILES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun write(capture: EvidenceCapture): File? =
        write(TelemetryEvidenceFormat.toJson(capture), CAPTURE_KIND, capture.firmware)

    /**
     * Any probe artifact, not only a signal capture.
     *
     * Every probe writes its JSON here because this folder is what the diagnostic export
     * bundles onto the USB stick; the retention below is one shared pool, so the newest
     * artifacts of every probe are the ones that leave the car.
     */
    fun write(json: String, kind: String, firmware: String): File? {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (maxFiles <= 0 || !prepareDirectory()) return null
        val target = uniqueTarget(kind, firmware)
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (temp.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun uniqueTarget(kind: String, firmware: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(nowMs()))
        val safeFirmware = firmware.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val scenario = directory.parentFile
            ?.let(::VehicleTestContextStore)
            ?.read()
            ?.scenario
            ?.id
            ?: "unclassified"
        val base = "$kind-$safeFirmware-$scenario-$stamp"
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
        internal const val CAPTURE_KIND = "evidence"
        private val CAPTURE_NAME = Regex("^(.*-\\d{8}-\\d{6}-\\d{3})(?:-(\\d+))?$")
    }
}
