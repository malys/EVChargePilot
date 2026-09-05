package com.evsuite.chargepilot.route

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writing the configuration back out to a USB stick, the exact file [RoutingConfigImport] reads.
 *
 * The driver configured this car from a stick; a second car, a factory reset or the unstable
 * channel — a separate application id with its own preferences — means configuring it again, and
 * this head unit's keyboard makes retyping a 60-character key an unreasonable thing to ask twice.
 *
 * **This file carries the keys in clear text**, because that is what a file the app can import
 * has to be. The stick is now the secret: the header says so in the file itself, and the driver
 * is told so on screen. Nothing else about the car goes in — no trips, no positions, no
 * diagnostics, only the four values the settings screen already owns.
 */
object RoutingConfigExport {

    /** One fixed name, overwritten: several dated exports and an import picks the oldest one. */
    const val FILE_NAME = "evchargepilot-routing.txt"

    /**
     * `key = value` in the spelling [RoutingConfig.parse] reads first, with a comment header a
     * driver reading the stick on a laptop cannot miss. Absent values are left out rather than
     * written empty, so importing this file back cannot blank a key it never carried.
     */
    fun format(config: RoutingConfig): String = buildString {
        appendLine("# EVChargePilot routing configuration.")
        appendLine("# It contains your API keys in clear text: this stick is now a secret.")
        appendLine("# Import it with 'Import from USB' on the routing key screen.")
        config.apiKey?.let { appendLine("ors_api_key  = $it") }
        config.baseUrl?.let { appendLine("ors_base_url = $it") }
        config.chargerApiKey?.let { appendLine("ocm_api_key  = $it") }
        config.chargerBaseUrl?.let { appendLine("ocm_base_url = $it") }
    }

    /**
     * Writes [config] into [directory], replacing an earlier export. Temp file then rename, so a
     * stick pulled mid-write leaves either the old configuration or the new one, never half a key.
     */
    fun write(directory: File, config: RoutingConfig): File? {
        if (!directory.isDirectory || config.isEmpty()) return null
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(format(config).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            // FAT has no atomic replace: the old file goes before the new one takes its name.
            target.delete()
            if (temp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
