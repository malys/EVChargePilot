package com.evsuite.chargepilot.route

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Reading the favourites file the driver browsed to.
 *
 * Parsed the same way [RoutingConfigImport] reads the routing key: by content, not by name, so
 * a driver who renamed the file still gets their list back. A line that is not
 * `label = longitude,latitude` is skipped rather than rejected, so one bad line does not lose
 * the rest of the list. A coordinate that is not a real point on Earth is one of those bad
 * lines: this file is hand-editable, so it is the trust boundary the rest of the app relies on.
 */
object DestinationFavoritesImport {

    /** Big enough for [DestinationFavorites.MAX_FAVORITES] lines; a wrong file is refused first. */
    const val MAX_FILE_BYTES = 64 * 1024

    /** The favourites in one file, or an empty list when it holds none. */
    fun read(file: File): List<OrsGeocode.Place> {
        if (!isCandidate(file)) return emptyList()
        return runCatching {
            parse(
                file.inputStream().use { stream ->
                    val buffer = ByteArray(MAX_FILE_BYTES)
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = stream.read(buffer, filled, buffer.size - filled)
                        if (read == -1) break
                        filled += read
                    }
                    String(buffer, 0, filled, StandardCharsets.UTF_8)
                }
            )
        }.getOrDefault(emptyList())
    }

    fun parse(text: String): List<OrsGeocode.Place> =
        text.lineSequence()
            .mapNotNull { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val label = line.substring(0, separator).trim()
                val point = line.substring(separator + 1).trim().split(",")
                if (label.isEmpty() || point.size != 2) return@mapNotNull null
                val longitude = point[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                val latitude = point[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                // The same bounds [OrsGeocode.parse] holds its own answers to. A hand-edited
                // stick is the one source of a Place nothing has checked, and "NaN" and "999"
                // both parse as doubles — a route request would carry them off the planet.
                if (!longitude.isFinite() || longitude !in -180.0..180.0) return@mapNotNull null
                if (!latitude.isFinite() || latitude !in -90.0..90.0) return@mapNotNull null
                OrsGeocode.Place(label, longitude, latitude)
            }
            .take(DestinationFavorites.MAX_FAVORITES)
            .toList()

    private fun isCandidate(file: File): Boolean =
        file.isFile && file.canRead() && file.length() > 0 && file.length() <= MAX_FILE_BYTES
}
