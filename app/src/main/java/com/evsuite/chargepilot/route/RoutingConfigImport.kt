package com.evsuite.chargepilot.route

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Reading the config file the driver browsed to, because typing a key on this head unit is not a
 * thing anyone does twice.
 *
 * EVABRPUploader's comment still holds — the MG4's system picker answers "no apps can perform
 * this action" — so [com.evsuite.chargepilot.StorageBrowserDialog] is the picker and this reads
 * the exact file it returned. Nothing here searches storage: the driver points at a path, so a
 * file that is not a config is reported against that path instead of being silently skipped.
 *
 * The file is identified by parsing, not by name: a driver who calls it `ors.txt` gets the same
 * result as one who calls it `evchargepilot.conf`.
 */
object RoutingConfigImport {

    /**
     * The configuration in one file, or an empty one when that file cannot hold a config.
     *
     * Size is checked before reading, so a wrong pick — a huge binary sitting on the stick — is
     * never slurped in, and never yields a key parsed out of its first bytes.
     */
    fun read(file: File): RoutingConfig {
        if (!isCandidate(file)) return RoutingConfig()
        return runCatching {
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
    }

    private fun isCandidate(file: File): Boolean =
        file.isFile && file.canRead() && file.length() > 0 &&
            file.length() <= RoutingConfig.MAX_FILE_BYTES
}
