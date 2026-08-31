package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.ClimateSnapshot
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.Provenance
import com.evsuite.hardware.telemetry.TirePressureSnapshot
import com.evsuite.hardware.telemetry.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClimateReadingsTest {
    @Test fun `all-null snapshot keeps every climate field explained`() {
        val readings = ClimateReadings.of(snapshot())

        listOf(
            readings.outsideTemp, readings.cabinTemp, readings.batteryTemp, readings.hvacOn,
            readings.acOn, readings.autoOn, readings.fan, readings.driverTarget,
            readings.passengerTarget, readings.econOn, readings.recirculationOn,
        ).forEach { assertEquals(Provenance.UNAVAILABLE, it.provenance) }
        assertEquals(UnavailableReason.UNVALIDATED_FIRMWARE, readings.batteryTemp.reason)
        assertEquals(UnavailableReason.SIGNAL_ABSENT, readings.outsideTemp.reason)
    }

    @Test fun `partial climate snapshot preserves available fields independently`() {
        val readings = ClimateReadings.of(snapshot().copy(
            outsideTempCelsius = 18f,
            cabinTempCelsius = 21.5f,
            climate = climate(powerOn = true, acOn = false, econOn = true),
        ))

        assertEquals(18f, readings.outsideTemp.value)
        assertEquals(21.5f, readings.cabinTemp.value)
        assertEquals(true, readings.hvacOn.value)
        assertEquals(false, readings.acOn.value)
        assertEquals(true, readings.econOn.value)
        assertNull(readings.recirculationOn.value)
    }

    @Test fun `fan requires both current level and maximum`() {
        val complete = ClimateReadings.of(snapshot().copy(
            climate = climate(fanLevel = 3, fanLevelMax = 8),
        ))
        val unknownMaximum = ClimateReadings.of(snapshot().copy(
            climate = climate(fanLevel = 3, fanLevelMax = null),
        ))

        assertEquals(FanReading(3, 8), complete.fan.value)
        assertEquals(Provenance.UNAVAILABLE, unknownMaximum.fan.provenance)
        assertEquals(UnavailableReason.SIGNAL_ABSENT, unknownMaximum.fan.reason)
    }

    @Test fun `raw battery temperature stays hidden until exact firmware is validated`() {
        val readings = ClimateReadings.of(snapshot().copy(batteryTempCelsius = 27f))

        assertNull(readings.batteryTemp.value)
        assertEquals(UnavailableReason.UNVALIDATED_FIRMWARE, readings.batteryTemp.reason)
    }

    @Test fun `unknown firmware explains every missing field as unsupported`() {
        val readings = ClimateReadings.of(snapshot(FirmwareInfo.Gen.UNKNOWN))

        assertEquals(setOf(UnavailableReason.UNSUPPORTED_FIRMWARE), readings.unavailableReasons)
    }

    private fun snapshot(firmware: FirmwareInfo.Gen = FirmwareInfo.Gen.SWI68) = EnergySnapshot(
        timestampMs = 0L,
        firmware = firmware,
        socPercent = null,
        rangeKm = null,
        speedKmh = null,
        batteryPowerKw = null,
        outsideTempCelsius = null,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        batteryEnergyKwh = null,
        batteryCapacityKwh = null,
        odometerKm = null,
        chargePortConnected = null,
        chargingStatus = null,
        parked = null,
        climate = climate(),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )

    private fun climate(
        powerOn: Boolean? = null,
        acOn: Boolean? = null,
        autoOn: Boolean? = null,
        econOn: Boolean? = null,
        recirculationOn: Boolean? = null,
        fanLevel: Int? = null,
        fanLevelMax: Int? = null,
        driverTargetCelsius: Float? = null,
        passengerTargetCelsius: Float? = null,
    ) = ClimateSnapshot(
        powerOn, acOn, autoOn, econOn, recirculationOn, fanLevel, fanLevelMax,
        driverTargetCelsius, passengerTargetCelsius,
    )
}
