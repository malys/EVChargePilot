package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary

/** Unstable learns from every real trip; the shared store enforces count and byte ceilings. */
internal object AutomaticTripRetention {
    fun shouldStore(automaticallyDetected: Boolean, summary: EnergyTripSummary): Boolean =
        !automaticallyDetected ||
            summary.recordedDistanceKm?.let { it > 0.0 } == true

    /** CP-055 comparison still needs a substantial trip; retention no longer does. */
    internal const val MIN_EVIDENCE_DISTANCE_KM = 5.0
}
