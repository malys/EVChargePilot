package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.UnavailableReason

/**
 * One frame of the dashboard, with every figure carrying what kind of claim it is.
 *
 * The activity used to format nullable floats straight out of the snapshot, which worked
 * only because everything on screen happened to be a vehicle reading. It will not stay that
 * way: adaptive range, the climate share and the speed comparison are models, and they have
 * to arrive on screen visibly different from the state of charge. Mapping the snapshot into
 * [Provenanced] values here is what makes that difference impossible to forget later.
 */
data class DashboardReadings(
    val soc: Provenanced<Float>,
    val range: Provenanced<Float>,
    val adaptiveRange: Provenanced<Double>,
    val speed: Provenanced<Float>,
    val power: Provenanced<Float>,
    val outsideTemp: Provenanced<Float>,
    val batteryTemp: Provenanced<Float>,
    val instantConsumption: Provenanced<Double>,
    val tripDuration: Provenanced<Long>,
    val tripDistance: Provenanced<Double>,
    val tripEnergy: Provenanced<Double>,
    val tripRegen: Provenanced<Double>,
    val tripConsumption: Provenanced<Double>,
) {
    companion object {
        /** Nothing read yet: every field unavailable, none of them zero. */
        fun empty(): DashboardReadings {
            fun <T : Any> gap() = Provenanced.unavailable<T>(UnavailableReason.SIGNAL_ABSENT)
            return DashboardReadings(
                soc = gap(), range = gap(), adaptiveRange = gap(), speed = gap(), power = gap(),
                outsideTemp = gap(), batteryTemp = gap(), instantConsumption = gap(),
                tripDuration = gap(), tripDistance = gap(), tripEnergy = gap(),
                tripRegen = gap(), tripConsumption = gap(),
            )
        }

        fun of(
            snapshot: EnergySnapshot,
            trip: EnergyTripSummary?,
            instantConsumption: Provenanced<Double>,
            adaptiveRange: Provenanced<Double>,
        ): DashboardReadings = of(
            snapshot,
            trip,
            instantConsumption,
            adaptiveRange,
            isPowerValidated(snapshot.firmware),
        )

        internal fun of(
            snapshot: EnergySnapshot,
            trip: EnergyTripSummary?,
            instantConsumption: Provenanced<Double>,
            adaptiveRange: Provenanced<Double>,
            powerValidated: Boolean,
        ): DashboardReadings {
            // An unrecognised generation is not a car that stopped publishing: it is a car
            // this build was never taught to read. The screen says which.
            val absent = if (snapshot.firmware == FirmwareInfo.Gen.UNKNOWN) {
                UnavailableReason.UNSUPPORTED_FIRMWARE
            } else {
                UnavailableReason.SIGNAL_ABSENT
            }
            val powerReason = powerUnavailableReason(snapshot.firmware)
            fun <T : Any> powerDerived(value: T?): Provenanced<T> = if (powerValidated) {
                Provenanced.derived(value)
            } else {
                Provenanced.unavailable(powerReason)
            }
            return DashboardReadings(
                soc = Provenanced.measured(snapshot.socPercent, absent),
                range = Provenanced.measured(snapshot.rangeKm, absent),
                adaptiveRange = if (powerValidated) adaptiveRange
                    else Provenanced.unavailable(powerReason),
                speed = Provenanced.measured(snapshot.speedKmh, absent),
                power = if (powerValidated) {
                    Provenanced.measured(snapshot.batteryPowerKw, absent)
                } else {
                    Provenanced.unavailable(powerReason)
                },
                outsideTemp = Provenanced.measured(snapshot.outsideTempCelsius, absent),
                batteryTemp = Provenanced.measured(snapshot.batteryTempCelsius, absent),
                instantConsumption = if (powerValidated) instantConsumption
                    else Provenanced.unavailable(powerReason),
                // The trip figures are arithmetic over those readings, so they are derived,
                // and they are missing for a different reason: no trip is being recorded, or
                // no usable interval has accumulated yet.
                tripDuration = Provenanced.derived(trip?.durationMs),
                tripDistance = Provenanced.derived(trip?.recordedDistanceKm),
                tripEnergy = powerDerived(trip?.consumedKwh),
                tripRegen = powerDerived(trip?.regeneratedKwh),
                tripConsumption = powerDerived(trip?.averageConsumptionKwhPer100Km),
            )
        }

        fun isPowerValidated(firmware: FirmwareInfo.Gen): Boolean =
            CarPropertyEvidence.isValidated(
                CarPropertyEvidence.Signal.BATTERY_POWER_KW,
                firmware,
            )

        fun powerUnavailableReason(firmware: FirmwareInfo.Gen): UnavailableReason = when {
            firmware == FirmwareInfo.Gen.UNKNOWN -> UnavailableReason.UNSUPPORTED_FIRMWARE
            !isPowerValidated(firmware) -> UnavailableReason.UNVALIDATED_FIRMWARE
            else -> UnavailableReason.SIGNAL_ABSENT
        }

        /** Models may reuse only totals produced by this exact firmware/conversion pair. */
        fun trustedPowerTrips(
            trips: List<EnergyTripSummary>,
            evidence: BatteryPowerEvidence?,
        ): List<EnergyTripSummary> = if (evidence == null) {
            emptyList()
        } else {
            trips.filter { it.batteryPowerEvidence == evidence }
        }
    }
}
