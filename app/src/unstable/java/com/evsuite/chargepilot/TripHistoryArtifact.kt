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
    /** The bundle's own answer to the speed-unit question, when a session can support one. */
    val speedScaleCheck: SpeedScaleCheck.Result,
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
            "The signal capture in the same bundle carries nav_adapter_odometer_km, read " +
                "from the navigation adapter rather than from PERF_ODOMETER. Its span over a " +
                "drive is a distance nothing derived from speed, so max minus min there " +
                "against distanceKm here is the speed-scale check that needs no observer.",
        )

        fun of(
            context: Context,
            nowMs: Long = System.currentTimeMillis(),
            sessionStartedAtMs: Long? = SignalEvidenceRecorder.sessionStartedAtMs,
            odometerSpanKm: Double? = SignalEvidenceRecorder.adapterOdometerSpanKm(),
        ): TripHistoryArtifact {
            val summaries = EnergyTripHistoryStore(File(context.filesDir, HISTORY_FILE))
                .readSummaries()
            // Only trips this session recorded can be compared against this session's odometer
            // span. A trip from yesterday shares no clock with it.
            val integratedKm = sessionStartedAtMs?.let { since ->
                summaries.filter { it.endedAtMs >= since && it.distanceAvailable != false }
                    .takeIf { it.isNotEmpty() }
                    ?.sumOf { it.distanceKm }
            }
            return TripHistoryArtifact(
                savedAtMs = nowMs,
                trips = summaries.size,
                speedScaleCheck = SpeedScaleCheck.of(odometerSpanKm, integratedKm),
                summaries = summaries.take(MAX_SUMMARIES),
            )
        }
    }
}
