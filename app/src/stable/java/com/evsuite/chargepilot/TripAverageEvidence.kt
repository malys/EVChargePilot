package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary

/** Stable records no development evidence. */
internal object TripAverageEvidence {
    fun record(@Suppress("UNUSED_PARAMETER") summary: EnergyTripSummary) = Unit
}
