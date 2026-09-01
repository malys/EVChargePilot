package com.evsuite.chargepilot

import java.io.File
import java.io.FileOutputStream

/** One overwrite-only marker correlating a CP-003 capture with its physical test scenario. */
internal class VehicleTestContextStore(private val filesDirectory: File) {

    data class Context(val scenario: Scenario, val selectedAtMs: Long)

    enum class Scenario(val id: String) {
        STATIONARY_HVAC_OFF("stationary-hvac-off"),
        STATIONARY_HVAC_MAX("stationary-hvac-max"),
        URBAN_ACCEL_REGEN("urban-accel-regen"),
        MOTORWAY_110("motorway-110"),
        MOTORWAY_130("motorway-130"),
        GRADE_UPHILL("grade-uphill"),
        GRADE_DOWNHILL("grade-downhill"),
        CHARGING("charging"),
        OTHER("other"),
    }

    fun write(scenario: Scenario, nowMs: Long = System.currentTimeMillis()): Boolean {
        val target = File(filesDirectory, FILE_NAME)
        val temp = File(filesDirectory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        return try {
            val content = "schema=1\nscenario=${scenario.id}\nselected_epoch_ms=$nowMs\n"
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            temp.renameTo(target)
        } catch (_: Exception) {
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun read(): Context? {
        val values = runCatching {
            File(filesDirectory, FILE_NAME).takeIf { it.isFile && it.length() in 1..MAX_BYTES }
                ?.readLines()
                ?.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.take(separator) to line.drop(separator + 1)
                }
                ?.toMap()
        }.getOrNull() ?: return null
        if (values["schema"] != "1") return null
        val scenario = Scenario.entries.firstOrNull { it.id == values["scenario"] } ?: return null
        val selectedAt = values["selected_epoch_ms"]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: return null
        return Context(scenario, selectedAt)
    }

    private companion object {
        const val FILE_NAME = "vehicle-test-context.txt"
        const val MAX_BYTES = 256L
    }
}
