package com.evsuite.chargepilot

import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.telemetry.EnergyTripSummary
import java.util.Locale

/** Captures the three non-equivalent trip averages at the same end-of-trip instant. */
internal object TripAverageEvidence {
    fun record(summary: EnergyTripSummary) {
        if (
            (summary.recordedDistanceKm ?: 0.0) <=
            AutomaticTripRetention.MIN_EVIDENCE_DISTANCE_KM
        ) return
        ValidationProbe.record(ValidationQuestion.SOC_SEGMENTS) {
            describe(summary, SaicCharging.consumptionPerKm())
        }
    }

    internal fun describe(summary: EnergyTripSummary, vehicleRaw: Float?): String {
        val distance = summary.recordedDistanceKm
        val gross = summary.averageConsumptionKwhPer100Km
        val consumed = summary.consumedKwh
        val regenerated = summary.regeneratedKwh
        val net = if (
            distance != null && distance >= MIN_DISTANCE_KM &&
            consumed != null && regenerated != null
        ) {
            (consumed - regenerated) * PER_100_KM / distance
        } else {
            null
        }
        return "trip average: distance_km=${format(distance)}" +
            " app_gross_kwh_per_100km=${format(gross)}" +
            " app_net_kwh_per_100km=${format(net)}" +
            " vehicle_consumption_per_km_raw=${format(vehicleRaw?.toDouble())}"
    }

    private fun format(value: Double?): String =
        value?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"

    private const val MIN_DISTANCE_KM = 0.1
    private const val PER_100_KM = 100.0
}
