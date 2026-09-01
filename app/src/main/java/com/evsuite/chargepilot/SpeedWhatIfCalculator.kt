package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.model.EnergyModel
import kotlin.math.max
import kotlin.math.min

enum class SpeedWhatIfUnavailable {
    MODEL_NOT_TRAINED,
    NO_MOTORWAY_PORTION,
    MOTORWAY_ENERGY_UNAVAILABLE,
    MOTORWAY_TEMPERATURE_UNAVAILABLE,
    RANGE_BASELINE_UNAVAILABLE,
    NO_REFERENCE_SPEED_IN_ENVELOPE,
}

sealed interface SpeedWhatIfResult {
    data class Ready(
        val motorwayDistanceKm: Double,
        val comparisons: List<SpeedWhatIfComparison>,
    ) : SpeedWhatIfResult

    data class Unavailable(val reason: SpeedWhatIfUnavailable) : SpeedWhatIfResult
}

data class SpeedWhatIfComparison(
    val referenceSpeedKmh: Int,
    val modelledEnergyLowKwh: Double,
    val modelledEnergyHighKwh: Double,
    val energyDeltaLowKwh: Double,
    val energyDeltaHighKwh: Double,
    val rangeDeltaLowKm: Double,
    val rangeDeltaHighKm: Double,
)

/** Pure post-trip comparison. No route, live prompt, or value outside model evidence. */
object SpeedWhatIfCalculator {
    private data class MotorwayInterval(
        val distanceKm: Double,
        val consumedKwh: Double,
        val outsideTempCelsius: Double,
    )

    fun calculate(
        trip: StoredTrip,
        model: EnergyModel?,
        referenceSpeedsKmh: List<Int> = REFERENCE_SPEEDS_KMH,
    ): SpeedWhatIfResult {
        if (model == null || trip.summary.batteryPowerEvidence != model.evidence) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.MODEL_NOT_TRAINED)
        }
        val samples = trip.samples.orEmpty()
        if (samples.size < 2) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }

        val intervals = ArrayList<MotorwayInterval>()
        var sawMotorway = false
        var sawMissingPower = false
        var sawMissingTemperature = false
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val previousSpeed = previous.speedKmh?.toDouble()
            val currentSpeed = current.speedKmh?.toDouble()
            val durationMs = current.atMs - previous.atMs
            if (previousSpeed == null || currentSpeed == null ||
                !previousSpeed.isFinite() || !currentSpeed.isFinite() ||
                previousSpeed < MOTORWAY_MIN_SPEED_KMH || currentSpeed < MOTORWAY_MIN_SPEED_KMH ||
                durationMs !in 1..MAX_SAMPLE_GAP_MS
            ) {
                continue
            }
            sawMotorway = true
            val previousPower = previous.batteryPowerKw?.toDouble()
            val currentPower = current.batteryPowerKw?.toDouble()
            if (previousPower == null || currentPower == null ||
                !previousPower.isFinite() || !currentPower.isFinite()
            ) {
                sawMissingPower = true
                continue
            }
            val previousTemp = previous.outsideTempCelsius?.toDouble()
            val currentTemp = current.outsideTempCelsius?.toDouble()
            if (previousTemp == null || currentTemp == null ||
                !previousTemp.isFinite() || !currentTemp.isFinite()
            ) {
                sawMissingTemperature = true
                continue
            }
            val hours = durationMs / MILLIS_PER_HOUR
            intervals += MotorwayInterval(
                distanceKm = (previousSpeed + currentSpeed) / 2.0 * hours,
                consumedKwh = (max(0.0, previousPower) + max(0.0, currentPower)) / 2.0 * hours,
                outsideTempCelsius = (previousTemp + currentTemp) / 2.0,
            )
        }
        if (!sawMotorway) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }
        if (sawMissingPower) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_ENERGY_UNAVAILABLE,
            )
        }
        if (sawMissingTemperature) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE,
            )
        }
        val baselineConsumption = trip.summary.averageConsumptionKwhPer100Km
        if (baselineConsumption == null || !baselineConsumption.isFinite() || baselineConsumption <= 0.0) {
            return SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.RANGE_BASELINE_UNAVAILABLE)
        }

        val actualEnergy = intervals.sumOf { it.consumedKwh }
        val comparisons = referenceSpeedsKmh.distinct().sorted().mapNotNull { referenceSpeed ->
            var modelledLow = 0.0
            var modelledHigh = 0.0
            for (interval in intervals) {
                val prediction = model.predict(
                    referenceSpeed.toDouble(),
                    interval.outsideTempCelsius,
                )
                val low = prediction.bandLow ?: return@mapNotNull null
                val high = prediction.bandHigh ?: return@mapNotNull null
                modelledLow += max(0.0, low) * interval.distanceKm / 100.0
                modelledHigh += max(0.0, high) * interval.distanceKm / 100.0
            }
            val deltaLow = actualEnergy - modelledHigh
            val deltaHigh = actualEnergy - modelledLow
            val rangeA = deltaLow * 100.0 / baselineConsumption
            val rangeB = deltaHigh * 100.0 / baselineConsumption
            SpeedWhatIfComparison(
                referenceSpeedKmh = referenceSpeed,
                modelledEnergyLowKwh = modelledLow,
                modelledEnergyHighKwh = modelledHigh,
                energyDeltaLowKwh = min(deltaLow, deltaHigh),
                energyDeltaHighKwh = max(deltaLow, deltaHigh),
                rangeDeltaLowKm = min(rangeA, rangeB),
                rangeDeltaHighKm = max(rangeA, rangeB),
            )
        }
        if (comparisons.isEmpty()) {
            return SpeedWhatIfResult.Unavailable(
                SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE,
            )
        }
        return SpeedWhatIfResult.Ready(intervals.sumOf { it.distanceKm }, comparisons)
    }

    val REFERENCE_SPEEDS_KMH = listOf(90, 100, 110, 120, 130)
    private const val MOTORWAY_MIN_SPEED_KMH = 90.0
    private const val MAX_SAMPLE_GAP_MS = 120_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0
}
