package com.evsuite.chargepilot.route

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writing the favourites list back out to a USB stick, the exact file
 * [DestinationFavoritesImport] reads.
 *
 * Same purpose [RoutingConfigExport] serves for the routing key: the list lives only in this
 * app's own preferences, and this is the one copy that leaves, so a second car or a factory
 * reset does not mean researching every address again.
 *
 * ```
 * # EVChargePilot favourite destinations.
 * # label = longitude,latitude
 * Home = 5.4474,43.5297
 * Office, 12 Rue de la Paix = 3.8767,43.6108
 * ```
 *
 * `label = longitude,latitude`, the coordinate order [OrsGeocode.Place] already returns. The
 * separator is `=`, never a comma, so a label copied from a full address — commas and all —
 * still parses back on import.
 */
object DestinationFavoritesExport {

    /** One fixed name, overwritten: several dated exports and an import picks the oldest one. */
    const val FILE_NAME = "evchargepilot-destinations.txt"

    fun format(favorites: List<OrsGeocode.Place>): String = buildString {
        appendLine("# EVChargePilot favourite destinations.")
        appendLine("# label = longitude,latitude")
        appendLine("# Import it with 'Import from USB' on the charging-stop screen.")
        favorites.forEach { appendLine("${it.label} = ${it.longitude},${it.latitude}") }
    }

    /**
     * Writes [favorites] into [directory], replacing an earlier export. Temp file then rename,
     * so a stick pulled mid-write leaves either the old list or the new one, never half a line.
     */
    fun write(directory: File, favorites: List<OrsGeocode.Place>): File? {
        if (!directory.isDirectory || favorites.isEmpty()) return null
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(format(favorites).toByteArray(Charsets.UTF_8))
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
