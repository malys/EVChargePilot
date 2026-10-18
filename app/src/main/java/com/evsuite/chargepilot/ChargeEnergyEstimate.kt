package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.ChargeEnergy
import kotlin.math.abs

/**
 * What a charge put in the pack, measured when the app watched it and estimated when it slept.
 *
 * A head unit asleep on the charger integrated nothing, so a watched charge's pack integral is
 * the only measurement there is. The estimate for the others is the points gained times the
 * declared usable capacity — the method MG4Control uses for every charge — labelled as such,
 * with the declared capacity's own band. Its power is that energy over the session's span, an
 * average over the window between two readings rather than the charger's rate.
 */
data class ChargeEnergyEstimate(val kwh: Double, val powerKw: Double?, val measured: Boolean) {

    companion object {
        fun of(charge: ChargeEnergy, pack: BatteryCapacityConfig): ChargeEnergyEstimate? {
            val packDelta = charge.packDeltaKwh
            val power = charge.meanPowerKw
            if (charge.watched && packDelta != null && power != null) {
                return ChargeEnergyEstimate(abs(packDelta), power, measured = true)
            }
            val kwh = pack.socPercentForEnergy(1.0).value?.let { pointsPerKwh ->
                charge.session.gainedPercent / pointsPerKwh
            }?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val hours = charge.session.durationHours
            return ChargeEnergyEstimate(kwh, (kwh / hours).takeIf { hours > 0.0 }, measured = false)
        }
    }
}
