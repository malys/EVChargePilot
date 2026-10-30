package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.DrivingStress
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.model.EnergyModelTrainer
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
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
    /** Same model gate production screens use, evaluated without opening one. */
    val socModelFit: String,
    /** The kWh model's gates, as [socModelFit]: the Details page only says "no model". */
    val powerModelFit: String = "",
    /**
     * The fit's own training points, `[km/h, °C, %/100 km, km]`, so a refusal can be replayed
     * off the car. The 2026-10-22 `fit=refused(aero)` could not be: only trip totals left it.
     */
    val socSegments: List<List<Double>> = emptyList(),
    val notes: List<String> = NOTES,
    val summaries: List<EnergyTripSummary>,
    /** CP-090: [DrivingStress] for each exported trip that kept a sample track. */
    val drivingStress: List<TripStress>,
) {
    /** Both figures null when [DrivingStress.of] could not say; Gson then omits them. */
    data class TripStress(
        val startedAtMs: Long,
        val stressedMinutes: Double?,
        val coldRegeneratedKwh: Double?,
    )

    fun toJson(): String = GSON.toJson(this)

    companion object {
        const val SCHEMA_VERSION = 4
        const val PROBE = "trip-history"
        const val KIND = "trips"

        private const val HISTORY_FILE = "trips.json"

        /** Enough to cover a validation session without turning the bundle into a database. */
        private const val MAX_SUMMARIES = 20

        /** Four numbers each: a thousand segments is a few tens of kilobytes. */
        private const val MAX_SEGMENTS = 1_000

        private fun round(value: Double, scale: Double) = kotlin.math.round(value * scale) / scale

        private val GSON = Gson()

        private val NOTES = listOf(
            "distanceKm is integrated from speedKmh, so a wrong speed scale shows up here " +
                "before anywhere else: divide distanceKm by durationMs to get the average " +
                "speed the integration believed, and compare it with the drive.",
            "The signal capture in the same bundle carries nav_adapter_odometer_km, read " +
                "from the navigation adapter rather than from PERF_ODOMETER. Its span over a " +
                "drive is a distance nothing derived from speed, so max minus min there " +
                "against distanceKm here is the speed-scale check that needs no observer.",
            "drivingStress has one entry per exported trip with a sample track, by " +
                "startedAtMs. No stressedMinutes means the measurement could not say for that " +
                "trip; no coldRegeneratedKwh means the outside temperature was never read. " +
                "Neither is a zero.",
        )

        fun of(
            context: Context,
            nowMs: Long = System.currentTimeMillis(),
            sessionStartedAtMs: Long? = SignalEvidenceRecorder.sessionStartedAtMs,
            odometerSpanKm: Double? = SignalEvidenceRecorder.adapterOdometerSpanKm(),
        ): TripHistoryArtifact {
            val history = EnergyTripHistoryStore(File(context.filesDir, HISTORY_FILE)).read()
            val summaries = history.map { it.summary }
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
                socModelFit = SocConsumptionFitter().describe(history),
                powerModelFit = EnergyModelTrainer().describe(history, FirmwareInfo.getGeneration()),
                socSegments = SocConsumptionFitter().segments(history).take(MAX_SEGMENTS).map {
                    listOf(
                        round(it.meanSpeedKmh, 10.0),
                        round(it.meanOutsideTempCelsius, 10.0),
                        round(it.percentPer100Km, 100.0),
                        round(it.distanceKm, 100.0),
                    )
                },
                summaries = summaries.take(MAX_SUMMARIES),
                drivingStress = history.take(MAX_SUMMARIES)
                    .filter { !it.samples.isNullOrEmpty() }
                    .map { trip ->
                        val stress = DrivingStress.of(trip.samples.orEmpty())
                        TripStress(
                            trip.summary.startedAtMs,
                            stress?.stressedMinutes,
                            stress?.coldRegeneratedKwh,
                        )
                    },
            )
        }
    }
}
