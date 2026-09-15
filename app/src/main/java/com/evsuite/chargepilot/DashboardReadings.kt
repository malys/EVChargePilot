package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
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
    val climate: ClimateReadings,
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
                climate = ClimateReadings.empty(), instantConsumption = gap(),
                tripDuration = gap(), tripDistance = gap(), tripEnergy = gap(),
                tripRegen = gap(), tripConsumption = gap(),
            )
        }

        fun of(
            snapshot: EnergySnapshot,
            trip: EnergyTripSummary?,
            instantConsumption: Provenanced<Double>,
            adaptiveRange: Provenanced<Double>,
            pack: BatteryCapacityConfig? = null,
        ): DashboardReadings = of(
            snapshot,
            trip,
            instantConsumption,
            adaptiveRange,
            isPowerValidated(snapshot.firmware),
            pack,
        )

        internal fun of(
            snapshot: EnergySnapshot,
            trip: EnergyTripSummary?,
            instantConsumption: Provenanced<Double>,
            adaptiveRange: Provenanced<Double>,
            powerValidated: Boolean,
            pack: BatteryCapacityConfig? = null,
        ): DashboardReadings {
            // An unrecognised generation is not a car that stopped publishing: it is a car
            // this build was never taught to read. The screen says which.
            val absent = if (snapshot.firmware == FirmwareInfo.Gen.UNKNOWN) {
                UnavailableReason.UNSUPPORTED_FIRMWARE
            } else {
                UnavailableReason.SIGNAL_ABSENT
            }
            val powerReason = powerUnavailableReason(snapshot.firmware)
            // A power reading nobody has confirmed on this firmware is still a reading. On
            // SWI68 it comes from pack voltage times pack current rather than from a property
            // a drive has proven, so it is published as arithmetic — derived, not measured —
            // instead of being withheld. Withholding it left instantaneous power, trip energy
            // and everything downstream of them empty on the only firmware this car runs.
            // An unrecognised generation is the exception: there, a number is not a reading
            // this build knows how to interpret at all, and it stays off the screen.
            val hasLivePower = snapshot.batteryPowerKw != null &&
                snapshot.firmware != FirmwareInfo.Gen.UNKNOWN
            val powerUsable = powerValidated || hasLivePower
            fun <T : Any> powerDerived(value: T?): Provenanced<T> = if (powerUsable) {
                Provenanced.derived(value)
            } else {
                Provenanced.unavailable(powerReason)
            }
            val socEnergy = socEnergyKwh(trip, pack)
            return DashboardReadings(
                soc = Provenanced.measured(snapshot.socPercent, absent),
                range = Provenanced.measured(snapshot.rangeKm, absent),
                adaptiveRange = if (powerUsable) adaptiveRange
                    else Provenanced.unavailable(powerReason),
                speed = Provenanced.measured(snapshot.speedKmh, absent),
                power = when {
                    powerValidated -> Provenanced.measured(snapshot.batteryPowerKw, absent)
                    hasLivePower -> Provenanced.derived(snapshot.batteryPowerKw)
                    else -> Provenanced.unavailable(powerReason)
                },
                climate = ClimateReadings.of(snapshot),
                instantConsumption = if (powerUsable) instantConsumption
                    else Provenanced.unavailable(powerReason),
                // The trip figures are arithmetic over those readings, so they are derived,
                // and they are missing for a different reason: no trip is being recorded, or
                // no usable interval has accumulated yet.
                tripDuration = Provenanced.derived(trip?.durationMs),
                tripDistance = Provenanced.derived(trip?.recordedDistanceKm),
                // Regeneration has no state-of-charge equivalent — a pack that gained and
                // spent energy in the same trip shows one net drop — so it keeps the gap.
                tripEnergy = orElse(powerDerived(trip?.consumedKwh), socEnergy),
                tripRegen = powerDerived(trip?.regeneratedKwh),
                tripConsumption = orElse(
                    powerDerived(trip?.averageConsumptionKwhPer100Km),
                    per100Km(socEnergy, trip?.recordedDistanceKm),
                ),
            )
        }

        /**
         * The energy a trip spent, read off the state of charge instead of off the power.
         *
         * No power interval is no reason to leave a finished trip blank: the pack dropped a
         * measured number of percent, and the driver has declared what a percent is worth.
         * That capacity is a specification and its state of health somebody's opinion, so the
         * figure is an estimate and arrives with the band [BatteryCapacityConfig] puts on it.
         */
        private fun socEnergyKwh(
            trip: EnergyTripSummary?,
            pack: BatteryCapacityConfig?,
        ): Provenanced<Double> {
            val start = trip?.startSocPercent?.toDouble()
            val end = trip?.endSocPercent?.toDouble()
            if (pack == null || start == null || end == null || start - end < MIN_SOC_DROP) {
                return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
            }
            return pack.energyAtSocKwh(start - end)
        }

        /** An energy estimate spread over a distance, band and all. */
        private fun per100Km(
            energy: Provenanced<Double>,
            distanceKm: Double?,
        ): Provenanced<Double> {
            val kwh = energy.value
            if (kwh == null || distanceKm == null || distanceKm < MIN_DISTANCE_KM) {
                return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
            }
            val scale = 100.0 / distanceKm
            return Provenanced.estimated(kwh * scale, (energy.uncertainty ?: 0.0) * scale)
        }

        /** The estimate only when the better claim has nothing; a gap keeps its own reason. */
        private fun orElse(
            preferred: Provenanced<Double>,
            fallback: Provenanced<Double>,
        ): Provenanced<Double> =
            if (preferred.isAvailable || !fallback.isAvailable) preferred else fallback

        /** Below this the state of charge has not moved enough to mean anything. */
        private const val MIN_SOC_DROP = 0.5

        /** Matches [EnergyTripSummary.averageConsumptionKwhPer100Km]'s own floor. */
        private const val MIN_DISTANCE_KM = 0.1

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
