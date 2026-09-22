package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary

/** Stable keeps the existing automatic-trip behaviour unchanged. */
internal object AutomaticTripRetention {
    fun shouldStore(
        @Suppress("UNUSED_PARAMETER") automaticallyDetected: Boolean,
        @Suppress("UNUSED_PARAMETER") summary: EnergyTripSummary,
    ): Boolean = true
}
