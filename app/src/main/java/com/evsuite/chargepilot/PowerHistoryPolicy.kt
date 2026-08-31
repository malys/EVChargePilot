package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.telemetry.EnergySnapshot

/** The only ingress from a raw power sample into normal trip persistence. */
internal object PowerHistoryPolicy {
    fun sanitize(
        value: EnergySnapshot,
        evidence: BatteryPowerEvidence? = CarPropertyEvidence.batteryPowerEvidence(value.firmware),
    ): EnergySnapshot = if (value.batteryPowerKw == null || evidence != null) {
        value
    } else {
        value.copy(batteryPowerKw = null)
    }
}
