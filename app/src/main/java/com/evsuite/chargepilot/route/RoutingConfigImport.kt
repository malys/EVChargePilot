package com.evsuite.chargepilot.route

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Finding the config file on a USB stick, because typing a key on this head unit is not a thing
 * anyone does twice.
 *
 * EVABRPUploader solved exactly this and its comment is the reason: the MG4's system picker
 * answers "no apps can perform this action", so the file is found by scanning rather than
 * chosen. `getExternalFilesDirs(null)` returns this app's own folder on internal storage *and*
 * on every mounted volume including the stick, and needs no storage permission at any API level.
 *
 * The file is identified by parsing, not by name: a driver who calls it `ors.txt` gets the same
 * result as one who calls it `evchargepilot.conf`, and a file that is not a config is skipped
 * rather than reported as a broken one.
 */
object RoutingConfigImport {

    data class Found(val file: File, val config: RoutingConfig)

    /** The first readable config in these directories, top level only. */
    fun search(directories: List<File>): Found? {
        for (directory in directories) {
            val files = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (file in files.sortedBy { it.name.lowercase() }) {
                if (!isCandidate(file)) continue
                val config = read(file)
                if (!config.isEmpty()) return Found(file, config)
            }
        }
        return null
    }

    /** Reads at most [RoutingConfig.MAX_FILE_BYTES]; anything larger was never a config. */
    fun read(file: File): RoutingConfig = runCatching {
        RoutingConfig.parse(
            file.inputStream().use { stream ->
                val buffer = ByteArray(RoutingConfig.MAX_FILE_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read == -1) break
                    filled += read
                }
                String(buffer, 0, filled, StandardCharsets.UTF_8)
            }
        )
    }.getOrDefault(RoutingConfig())

    private fun isCandidate(file: File): Boolean =
        file.isFile && file.canRead() && file.length() > 0 &&
            file.length() <= RoutingConfig.MAX_FILE_BYTES
}
