package com.evsuite.chargepilot

import java.io.File

/**
 * Bounds what the stores leave behind in the app's private files directory.
 *
 * A store that cannot parse its file renames it `<name>.quarantine.<ms>` and starts again, and a
 * write cut by the head unit powering off leaves its `<name>.<nanos>.tmp`. Neither is ever read
 * again. On the car, an R8 build that could not read its own ledger (fixed 2026-10-11) left more
 * than four hundred of them, invisible to the driver and growing with every boot.
 *
 * The newest [KEEP_QUARANTINED] copies per file stay, so a returned bundle can still show one,
 * and so does any copy over [SMALL_COPY_BYTES]: that one may hold a history worth recovering.
 * A temporary file is removed once it is older than [STALE_TMP_MS]: a live write lasts
 * milliseconds. Nothing runs before the clock is set, because then every age is fiction.
 */
object StorageHygiene {

    const val KEEP_QUARANTINED = 5
    const val STALE_TMP_MS = 60 * 60 * 1000L

    // ponytail: size stands in for "nothing worth recovering"; the leftovers are 88-139 bytes.
    const val SMALL_COPY_BYTES = 4_096L

    /** 2025-01-01T00:00Z, the floor the battery ledger uses for the same factory clock. */
    private const val MIN_PLAUSIBLE_CLOCK_MS = 1_735_689_600_000L

    private val QUARANTINED = Regex("""(.+)\.quarantine\.\d+""")
    private val TEMPORARY = Regex(""".+\.\d+\.tmp""")

    /** @return how many files were removed. */
    fun sweep(directory: File, nowMs: Long): Int {
        if (nowMs < MIN_PLAUSIBLE_CLOCK_MS) return 0
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        val surplus = files
            .mapNotNull { file -> QUARANTINED.matchEntire(file.name)?.let { it.groupValues[1] to file } }
            .groupBy({ it.first }, { it.second })
            .values
            .flatMap { copies -> copies.sortedByDescending(File::lastModified).drop(KEEP_QUARANTINED) }
            .filter { it.length() <= SMALL_COPY_BYTES }
        val stale = files.filter {
            TEMPORARY.matches(it.name) && nowMs - it.lastModified() > STALE_TMP_MS
        }
        return (surplus + stale).count(File::delete)
    }
}
