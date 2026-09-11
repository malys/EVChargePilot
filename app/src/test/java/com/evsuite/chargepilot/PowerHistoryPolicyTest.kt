package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.ClimateSnapshot
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.TirePressureSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The one door between a raw power reading and a trip that is kept.
 *
 * The dashboard can hide an unevidenced figure and show it again the moment a firmware is
 * validated. A trip file cannot: it is written once and read for the rest of the car's life, so
 * a number that nobody can say the conversion of must never enter it. What is dropped here is
 * dropped permanently, which is the point — an energy total computed from an unevidenced power
 * is indistinguishable on disk from one that was measured.
 */
class PowerHistoryPolicyTest {

    private val evidence = BatteryPowerEvidence(
        FirmwareInfo.Gen.SWI68,
        BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
    )

    @Test
    fun `an evidenced reading is kept exactly as it arrived`() {
        val sample = snapshot(powerKw = 42f)
        assertSame(sample, PowerHistoryPolicy.sanitize(sample, evidence))
    }

    @Test
    fun `a reading with no evidence behind it never reaches the trip file`() {
        val sanitized = PowerHistoryPolicy.sanitize(snapshot(powerKw = 42f), evidence = null)
        assertNull(sanitized.batteryPowerKw)
        // Only the power goes: the rest of the sample is a measurement in its own right.
        assertEquals(80f, sanitized.socPercent)
        assertEquals(50f, sanitized.speedKmh)
    }

    @Test
    fun `a sample that carried no power is untouched, evidence or not`() {
        val sample = snapshot(powerKw = null)
        assertSame(sample, PowerHistoryPolicy.sanitize(sample, evidence = null))
        assertSame(sample, PowerHistoryPolicy.sanitize(sample, evidence))
    }

    @Test
    fun `zero is a reading, not an absence — it survives when it is evidenced`() {
        assertEquals(0f, PowerHistoryPolicy.sanitize(snapshot(powerKw = 0f), evidence).batteryPowerKw)
        assertNull(PowerHistoryPolicy.sanitize(snapshot(powerKw = 0f), null).batteryPowerKw)
    }

    private fun snapshot(powerKw: Float?) = EnergySnapshot(
        timestampMs = 0L,
        firmware = FirmwareInfo.Gen.SWI68,
        socPercent = 80f,
        rangeKm = 300f,
        speedKmh = 50f,
        batteryPowerKw = powerKw,
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
