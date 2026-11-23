package com.evsuite.chargepilot

import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.DriverContract
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.StoredTrip
import java.io.File
import kotlin.math.roundToInt

/**
 * CP-098: which driver each stored trip belongs to, by the trip's `startedAtMs`.
 *
 * Its own file rather than a field on the trip: the trip type is EVHardware's, shared with every
 * app, and a driver name is personal data that must stay out of the trip export and the
 * diagnostic bundle — both of which read the trip history, never this file.
 *
 * One `startedAtMs<TAB>name` line per trip. A name holds no control character
 * ([DriverContract.normalizeName]), so neither a tab nor a newline can appear inside one.
 * Written atomically: temp file, then rename over the target.
 */
class TripDriverStore(private val file: File) {

    @Synchronized
    fun read(): Map<Long, String> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val startMs = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                val name = DriverContract.normalizeName(line.substring(tab + 1)) ?: return@mapNotNull null
                startMs to name
            }.toMap()
        }.onFailure { AppLogger.w(TAG, "trip drivers unreadable: ${it.message}") }.getOrDefault(emptyMap())
    }

    /** Files the trip under [name], or makes it Unknown when [name] is null. */
    @Synchronized
    fun assign(startedAtMs: Long, name: String?): Boolean {
        val drivers = read().toMutableMap()
        val normalized = name?.let { DriverContract.normalizeName(it) ?: return false }
        if (normalized == null) drivers.remove(startedAtMs) else drivers[startedAtMs] = normalized
        return write(drivers)
    }

    /**
     * Files a trip just saved under the driver EVTasker published, if any.
     *
     * [storedStartMs] is the key the trip was saved under: the leg's own start, or the start of
     * the drive it was joined onto. A joined drive keeps the driver of its first leg; a leg
     * with a different driver makes the whole drive Unknown rather than picking one.
     */
    @Synchronized
    fun attributeSaved(storedStartMs: Long, joined: Boolean, driver: String?) {
        val existing = read()[storedStartMs]
        val target = DriverAttribution.after(existing, joined, driver)
        if (target != existing) assign(storedStartMs, target)
    }

    /** Every trip filed under [name] becomes Unknown, in one write. */
    @Synchronized
    fun forget(name: String): Boolean = write(read().filterValues { it != name })

    private fun write(drivers: Map<Long, String>): Boolean {
        val temp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        return runCatching {
            temp.writeText(drivers.entries.joinToString("") { "${it.key}\t${it.value}\n" })
            check(temp.renameTo(file)) { "rename failed" }
            true
        }.onFailure {
            temp.delete()
            AppLogger.w(TAG, "trip drivers not written: ${it.message}")
        }.getOrDefault(false)
    }

    companion object {
        const val FILE_NAME = "trip_drivers.tsv"
        private const val TAG = "CP_DRIVERS"
    }
}

/** The pure rule behind [TripDriverStore.attributeSaved], so it runs on the JVM. */
object DriverAttribution {

    /** The driver a stored trip should carry once a leg with [legDriver] is saved onto it. */
    fun after(existing: String?, joined: Boolean, legDriver: String?): String? = when {
        !joined -> legDriver
        existing != null && legDriver != null && legDriver != existing -> null
        else -> existing
    }

    /** Trips filed under [driver]; null means every trip, [UNKNOWN] the ones nobody claims. */
    fun matches(drivers: Map<Long, String>, startedAtMs: Long, driver: String?): Boolean = when (driver) {
        null -> true
        UNKNOWN -> startedAtMs !in drivers
        else -> drivers[startedAtMs] == driver
    }

    /** Filter value for trips with no driver. A name cannot be empty, so it cannot collide. */
    const val UNKNOWN = ""
}

/** CP-098: one line of the per-driver table — trips, distance, consumption and eco score. */
data class DriverStats(
    /** A driver's name, or [DriverAttribution.UNKNOWN]. */
    val driver: String,
    val totals: TripTotals,
    /** Mean score of the trips that earned one; null when none did. */
    val averageScore: Int?,
) {
    companion object {
        /** Known drivers by name, then Unknown when it has trips. Empty when nobody is known. */
        fun of(
            trips: List<StoredTrip>,
            drivers: Map<Long, String>,
            verdicts: Map<Long, EcoVerdict?>,
        ): List<DriverStats> {
            val known = trips.mapNotNull { drivers[it.summary.startedAtMs] }.distinct().sorted()
            if (known.isEmpty()) return emptyList()
            return (known + DriverAttribution.UNKNOWN).mapNotNull { driver ->
                val mine = trips.filter { DriverAttribution.matches(drivers, it.summary.startedAtMs, driver) }
                if (mine.isEmpty()) return@mapNotNull null
                val scores = mine.mapNotNull {
                    EcoScore.of(it.summary, verdicts[it.summary.startedAtMs])?.total
                }
                DriverStats(
                    driver,
                    TripOverview.totals(mine.map { it.summary }),
                    scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                )
            }
        }
    }
}
