package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary

/** Unstable spends its bounded history budget on trips long enough to improve the local model. */
internal object AutomaticTripRetention {
    fun shouldStore(automaticallyDetected: Boolean, summary: EnergyTripSummary): Boolean =
        !automaticallyDetected ||
            summary.recordedDistanceKm?.let { it > MIN_DISTANCE_KM } == true

    internal const val MIN_DISTANCE_KM = 5.0
}
