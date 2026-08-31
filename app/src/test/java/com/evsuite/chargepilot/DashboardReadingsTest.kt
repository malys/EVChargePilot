package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.ClimateSnapshot
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenance
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.TirePressureSnapshot
import com.evsuite.hardware.telemetry.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardReadingsTest {
    @Test fun `unvalidated firmware hides raw and derived power figures`() {
        val readings = DashboardReadings.of(
            snapshot(FirmwareInfo.Gen.SWI68),
            trip(),
            Provenanced.derived(18.0),
            Provenanced.estimated(280.0, 20.0),
        )

        assertEquals(Provenance.MEASURED, readings.soc.provenance)
        listOf(
            readings.power,
            readings.instantConsumption,
            readings.adaptiveRange,
            readings.tripEnergy,
            readings.tripRegen,
            readings.tripConsumption,
        ).forEach { reading ->
            assertEquals(Provenance.UNAVAILABLE, reading.provenance)
            assertEquals(UnavailableReason.UNVALIDATED_FIRMWARE, reading.reason)
        }
    }

    @Test fun `unknown firmware reports unsupported rather than unvalidated power`() {
        val readings = DashboardReadings.of(
            snapshot(FirmwareInfo.Gen.UNKNOWN),
            trip(),
            Provenanced.derived(18.0),
            Provenanced.estimated(280.0, 20.0),
        )

        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, readings.power.reason)
        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, readings.tripEnergy.reason)
    }

    @Test fun `validated firmware exposes signed power and trip totals`() {
        val readings = DashboardReadings.of(
            snapshot(FirmwareInfo.Gen.SWI68),
            trip(),
            Provenanced.derived(18.0),
            Provenanced.estimated(280.0, 20.0),
            powerValidated = true,
        )

        assertEquals(Provenance.MEASURED, readings.power.provenance)
        assertEquals(42f, readings.power.value)
        assertEquals(Provenance.DERIVED, readings.instantConsumption.provenance)
        assertEquals(0.2, readings.tripEnergy.value)
        assertEquals(0.05, readings.tripRegen.value)
    }

    @Test fun `models reuse only history with matching power evidence`() {
        val swi68V1 = BatteryPowerEvidence(
            FirmwareInfo.Gen.SWI68,
            BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
        )
        val swi69V1 = BatteryPowerEvidence(
            FirmwareInfo.Gen.SWI69,
            BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
        )
        val legacy = trip()
        val matching = trip().copy(startedAtMs = 1L, batteryPowerEvidence = swi68V1)
        val otherFirmware = trip().copy(startedAtMs = 2L, batteryPowerEvidence = swi69V1)

        assertEquals(emptyList<EnergyTripSummary>(), DashboardReadings.trustedPowerTrips(
            listOf(legacy, matching, otherFirmware),
            null,
        ))
        assertEquals(
            listOf(matching),
            DashboardReadings.trustedPowerTrips(listOf(legacy, matching, otherFirmware), swi68V1),
        )
    }

    @Test fun `normal trip ingress removes unvalidated raw power and preserves proven power`() {
        val raw = snapshot(FirmwareInfo.Gen.SWI68)
        val evidence = BatteryPowerEvidence(
            FirmwareInfo.Gen.SWI68,
            BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
        )

        assertEquals(null, PowerHistoryPolicy.sanitize(raw, evidence = null).batteryPowerKw)
        assertEquals(42f, PowerHistoryPolicy.sanitize(raw, evidence).batteryPowerKw)
    }

    private fun trip() = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = 60_000L,
        durationMs = 60_000L,
        distanceKm = 1.0,
        startSocPercent = 80f,
        endSocPercent = 79f,
        consumedKwh = 0.2,
        regeneratedKwh = 0.05,
        distanceAvailable = true,
    )

    private fun snapshot(firmware: FirmwareInfo.Gen) = EnergySnapshot(
        timestampMs = 0L,
        firmware = firmware,
        socPercent = 80f,
        rangeKm = 300f,
        speedKmh = 50f,
        batteryPowerKw = 42f,
        outsideTempCelsius = 20f,
        cabinTempCelsius = null,
        batteryTempCelsius = 25f,
        batteryEnergyKwh = null,
        batteryCapacityKwh = null,
        odometerKm = null,
        chargePortConnected = null,
        chargingStatus = null,
        parked = false,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
