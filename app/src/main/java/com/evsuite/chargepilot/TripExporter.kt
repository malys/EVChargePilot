package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.Instant

/** Writes bounded, portable copies of completed trips into app-private storage. */
class TripExporter(
    private val exportDirectory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = MAX_EXPORT_BYTES,
    private val maxFiles: Int = MAX_EXPORT_FILES,
) {
    enum class Format(val extension: String, val mimeType: String) {
        CSV("csv", "text/csv"),
        JSON("json", "application/json"),
    }

    data class ExportedFile(
        val file: File,
        val mimeType: String,
        val tripCount: Int,
    )

    fun export(trips: List<StoredTrip>, format: Format, singleTrip: Boolean): Result<ExportedFile> =
        runCatching {
            synchronized(exportLock) {
                require(trips.isNotEmpty()) { "There are no trips to export." }
                require(trips.size <= MAX_TRIPS) {
                    "Trip export exceeds the $MAX_TRIPS-trip limit."
                }
                val exportedAtMs = nowMs()
                val bytes = when (format) {
                    Format.CSV -> csv(trips).toByteArray(Charsets.UTF_8)
                    Format.JSON -> json(trips, exportedAtMs).toByteArray(Charsets.UTF_8)
                }
                require(bytes.size <= maxBytes) { "Trip export exceeds the size limit." }
                require(exportDirectory.mkdirs() || exportDirectory.isDirectory) {
                    "The export directory is unavailable."
                }
                prepareDirectory()

                val scope = if (singleTrip) "trip" else "trips"
                val target = uniqueTarget("evchargepilot-$scope-$exportedAtMs.${format.extension}")
                val temp = File(exportDirectory, ".${target.name}.${System.nanoTime()}.tmp")
                try {
                    FileOutputStream(temp).use { output ->
                        output.write(bytes)
                        output.fd.sync()
                    }
                    check(temp.renameTo(target)) { "The export could not be finalized." }
                } finally {
                    temp.delete()
                }
                ExportedFile(target, format.mimeType, trips.size)
            }
        }

    /** Generated copies are reproducible, so retain only a small fixed set on disk. */
    private fun prepareDirectory() {
        require(maxFiles > 0) { "The export retention limit is invalid." }
        exportDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach { require(it.delete()) { "A stale export could not be removed." } }
        val exports = exportDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension in setOf("csv", "json") }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        exports.take((exports.size - maxFiles + 1).coerceAtLeast(0)).forEach {
            require(it.delete()) { "An old export could not be removed." }
        }
    }

    private fun csv(trips: List<StoredTrip>): String = buildString {
        append(CSV_HEADER)
        append('\n')
        trips.forEach { trip ->
            val summary = trip.summary
            append(
                listOf(
                    Instant.ofEpochMilli(summary.startedAtMs).toString(),
                    Instant.ofEpochMilli(summary.endedAtMs).toString(),
                    decimal(summary.durationMs / 1_000.0),
                    decimal(summary.recordedDistanceKm),
                    decimal(summary.startSocPercent),
                    decimal(summary.endSocPercent),
                    decimal(summary.consumedKwh),
                    decimal(summary.regeneratedKwh),
                    decimal(summary.averageConsumptionKwhPer100Km),
                ).joinToString(",")
            )
            append('\n')
        }
    }

    /** Explicit keys make the public export schema independent of R8 field renaming. */
    private fun json(trips: List<StoredTrip>, exportedAtMs: Long): String = JsonObject().apply {
        addProperty("schemaVersion", JSON_SCHEMA_VERSION)
        addProperty("exportedAtUtc", Instant.ofEpochMilli(exportedAtMs).toString())
        add("trips", JsonArray().apply { trips.forEach { add(jsonTrip(it)) } })
    }.toString()

    private fun jsonTrip(trip: StoredTrip) = JsonObject().apply {
        val summary = trip.summary
        add("summary", JsonObject().apply {
            addProperty("startedAtMs", summary.startedAtMs)
            addProperty("endedAtMs", summary.endedAtMs)
            addProperty("durationMs", summary.durationMs)
            addFiniteOrNull("distanceKm", summary.distanceKm)
            addFiniteOrNull("startSocPercent", summary.startSocPercent)
            addFiniteOrNull("endSocPercent", summary.endSocPercent)
            addFiniteOrNull("consumedKwh", summary.consumedKwh)
            addFiniteOrNull("regeneratedKwh", summary.regeneratedKwh)
            addNullable("distanceAvailable", summary.distanceAvailable)
        })
        add(
            "samples",
            trip.samples?.let { samples ->
                JsonArray().apply { samples.forEach { add(jsonSample(it)) } }
            } ?: JsonNull.INSTANCE,
        )
    }

    private fun jsonSample(sample: TripSample) = JsonObject().apply {
        addProperty("atMs", sample.atMs)
        addFiniteOrNull("speedKmh", sample.speedKmh)
        addFiniteOrNull("batteryPowerKw", sample.batteryPowerKw)
        addFiniteOrNull("socPercent", sample.socPercent)
        addFiniteOrNull("outsideTempCelsius", sample.outsideTempCelsius)
        addFiniteOrNull("cabinTempCelsius", sample.cabinTempCelsius)
        addFiniteOrNull("batteryTempCelsius", sample.batteryTempCelsius)
        addNullable("climatePowerOn", sample.climatePowerOn)
        addNullable("climateAcOn", sample.climateAcOn)
        sample.climateFanLevel?.let { addProperty("climateFanLevel", it) }
            ?: add("climateFanLevel", JsonNull.INSTANCE)
    }

    private fun JsonObject.addFiniteOrNull(name: String, value: Double?) {
        value?.takeIf(Double::isFinite)?.let { addProperty(name, it) }
            ?: add(name, JsonNull.INSTANCE)
    }

    private fun JsonObject.addFiniteOrNull(name: String, value: Float?) {
        value?.takeIf(Float::isFinite)?.let { addProperty(name, it) }
            ?: add(name, JsonNull.INSTANCE)
    }

    private fun JsonObject.addNullable(name: String, value: Boolean?) {
        value?.let { addProperty(name, it) } ?: add(name, JsonNull.INSTANCE)
    }

    private fun uniqueTarget(requestedName: String): File {
        val requested = File(exportDirectory, requestedName)
        if (!requested.exists()) return requested
        val stem = requested.nameWithoutExtension
        val extension = requested.extension
        var suffix = 2
        while (true) {
            val candidate = File(exportDirectory, "$stem-$suffix.$extension")
            if (!candidate.exists()) return candidate
            suffix += 1
        }
    }

    private fun decimal(value: Double?): String = value
        ?.takeIf(Double::isFinite)
        ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
        .orEmpty()

    private fun decimal(value: Float?): String = value
        ?.takeIf(Float::isFinite)
        ?.let { BigDecimal(it.toString()).stripTrailingZeros().toPlainString() }
        .orEmpty()

    companion object {
        const val CSV_HEADER =
            "started_at_utc,ended_at_utc,recorded_duration_seconds,distance_km," +
                "start_soc_percent,end_soc_percent,consumed_kwh,regenerated_kwh," +
                "average_consumption_kwh_per_100km"
        const val MAX_EXPORT_BYTES = 2 * 1024 * 1024
        const val MAX_TRIPS = 200
        const val MAX_EXPORT_FILES = 8
        private const val JSON_SCHEMA_VERSION = 1
        private val exportLock = Any()
    }
}
