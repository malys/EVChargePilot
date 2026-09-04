package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.google.gson.Gson
import java.io.File

/**
 * The recorded trips, carried out on the diagnostic bundle.
 *
 * `trips.json` lives in app-private storage and never left the car, so a wrong trip distance
 * could only be reported second-hand — "the app said more than 7 km for a 2.4 km drive" is a
 * bug report, not evidence. With the summaries in the bundle the arithmetic is checkable:
 * distance against duration gives the average speed the integration believed in, and that is
 * the number the speed-unit question turns on.
 *
 * Summaries only. The per-trip sample tracks are far larger than a diagnostic bundle should
 * carry, and nothing about a distance error needs them.
 */
internal data class TripHistoryArtifact(
    val schemaVersion: Int = SCHEMA_VERSION,
    val probe: String = PROBE,
    val savedAtMs: Long,
    val trips: Int,
    val notes: List<String> = NOTES,
    val summaries: List<EnergyTripSummary>,
) {
    fun toJson(): String = GSON.toJson(this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROBE = "trip-history"
        const val KIND = "trips"

        private const val HISTORY_FILE = "trips.json"

        /** Enough to cover a validation session without turning the bundle into a database. */
        private const val MAX_SUMMARIES = 20

        private val GSON = Gson()

        private val NOTES = listOf(
            "distanceKm is integrated from speedKmh, so a wrong speed scale shows up here " +
                "before anywhere else: divide distanceKm by durationMs to get the average " +
                "speed the integration believed, and compare it with the drive.",
        )

        fun of(context: Context, nowMs: Long = System.currentTimeMillis()): TripHistoryArtifact {
            val summaries = EnergyTripHistoryStore(File(context.filesDir, HISTORY_FILE))
                .readSummaries()
            return TripHistoryArtifact(
                savedAtMs = nowMs,
                trips = summaries.size,
                summaries = summaries.take(MAX_SUMMARIES),
            )
        }
    }
}
