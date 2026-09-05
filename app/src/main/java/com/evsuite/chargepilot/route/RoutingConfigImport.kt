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

    /**
     * Directories that are searched inside each volume, beyond its top level.
     *
     * A stick handed to a driver has the file wherever the person who wrote it put it, and the
     * head unit's own file manager drops downloads into folders of its own. One level down is the
     * difference between finding the key and an import that says "nothing here" on a stick that
     * plainly has it; two levels down is a scan of somebody's music library.
     */
    private const val MAX_DEPTH = 1

    /** The first readable config in these directories, or one level inside them. */
    fun search(directories: List<File>): Found? {
        for (directory in directories) {
            searchIn(directory, MAX_DEPTH)?.let { return it }
        }
        return null
    }

    private fun searchIn(directory: File, depth: Int): Found? {
        val entries = runCatching { directory.listFiles() }.getOrNull() ?: return null
        val sorted = entries.sortedBy { it.name.lowercase() }
        // Files before directories, so a config at the top level always wins over a deeper one.
        for (file in sorted) {
            if (!isCandidate(file)) continue
            val config = read(file)
            if (!config.isEmpty()) return Found(file, config)
        }
        if (depth <= 0) return null
        for (child in sorted) {
            if (!child.isDirectory || child.isHidden) continue
            searchIn(child, depth - 1)?.let { return it }
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
