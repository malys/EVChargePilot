package com.evsuite.chargepilot

import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.UnavailableReason

/** A fan setting is useful only when the scale's maximum is known. */
data class FanReading(val level: Int, val maximum: Int)

/**
 * Read-only thermal and climate state, with each field keeping its own availability.
 *
 * No value here is retained or interpreted as energy. In particular, HVAC state never
 * becomes an HVAC consumption figure: the vehicle has not provided one.
 */
data class ClimateReadings(
    val outsideTemp: Provenanced<Float>,
    val cabinTemp: Provenanced<Float>,
    val batteryTemp: Provenanced<Float>,
    val hvacOn: Provenanced<Boolean>,
    val acOn: Provenanced<Boolean>,
    val autoOn: Provenanced<Boolean>,
    val fan: Provenanced<FanReading>,
    val driverTarget: Provenanced<Float>,
    val passengerTarget: Provenanced<Float>,
    val econOn: Provenanced<Boolean>,
    val recirculationOn: Provenanced<Boolean>,
) {
    val unavailableReasons: Set<UnavailableReason>
        get() = listOf(
            outsideTemp, cabinTemp, batteryTemp, hvacOn, acOn, autoOn, fan,
            driverTarget, passengerTarget, econOn, recirculationOn,
        ).mapNotNullTo(linkedSetOf()) { it.reason }

    companion object {
        fun empty(): ClimateReadings = unavailable(UnavailableReason.SIGNAL_ABSENT)

        fun of(snapshot: EnergySnapshot): ClimateReadings {
            val absent = if (snapshot.firmware == FirmwareInfo.Gen.UNKNOWN) {
                UnavailableReason.UNSUPPORTED_FIRMWARE
            } else {
                UnavailableReason.SIGNAL_ABSENT
            }
            val climate = snapshot.climate
            val batteryTemperature = if (CarPropertyEvidence.isValidated(
                    CarPropertyEvidence.Signal.BATTERY_TEMPERATURE_CELSIUS,
                    snapshot.firmware,
                )
            ) {
                Provenanced.measured(snapshot.batteryTempCelsius, absent)
            } else {
                Provenanced.unavailable(batteryTemperatureReason(snapshot.firmware))
            }
            val fanLevel = climate.fanLevel
            val fanMaximum = climate.fanLevelMax
            val fan = if (fanLevel != null && fanMaximum != null) {
                Provenanced.measured(FanReading(fanLevel, fanMaximum))
            } else {
                Provenanced.unavailable<FanReading>(absent)
            }
            return ClimateReadings(
                outsideTemp = Provenanced.measured(snapshot.outsideTempCelsius, absent),
                cabinTemp = Provenanced.measured(snapshot.cabinTempCelsius, absent),
                batteryTemp = batteryTemperature,
                hvacOn = Provenanced.measured(climate.powerOn, absent),
                acOn = Provenanced.measured(climate.acOn, absent),
                autoOn = Provenanced.measured(climate.autoOn, absent),
                fan = fan,
                driverTarget = Provenanced.measured(climate.driverTargetCelsius, absent),
                passengerTarget = Provenanced.measured(climate.passengerTargetCelsius, absent),
                econOn = Provenanced.measured(climate.econOn, absent),
                recirculationOn = Provenanced.measured(climate.recirculationOn, absent),
            )
        }

        fun batteryTemperatureReason(firmware: FirmwareInfo.Gen): UnavailableReason =
            if (firmware == FirmwareInfo.Gen.UNKNOWN) {
                UnavailableReason.UNSUPPORTED_FIRMWARE
            } else {
                UnavailableReason.UNVALIDATED_FIRMWARE
            }

        private fun unavailable(reason: UnavailableReason): ClimateReadings {
            fun <T : Any> gap() = Provenanced.unavailable<T>(reason)
            return ClimateReadings(
                outsideTemp = gap(), cabinTemp = gap(), batteryTemp = gap(), hvacOn = gap(),
                acOn = gap(), autoOn = gap(), fan = gap(), driverTarget = gap(),
                passengerTarget = gap(), econOn = gap(), recirculationOn = gap(),
            )
        }
    }
}
