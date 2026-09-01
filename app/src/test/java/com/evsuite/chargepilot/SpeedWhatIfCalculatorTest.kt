package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.model.EnergyModel
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedWhatIfCalculatorTest {
    private val evidence = BatteryPowerEvidence(
        FirmwareInfo.Gen.SWI68,
        BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
    )

    @Test fun `only reference speeds inside the model envelope are returned with bands`() {
        val result = SpeedWhatIfCalculator.calculate(trip(), model()) as SpeedWhatIfResult.Ready

        assertEquals(listOf(100, 110), result.comparisons.map { it.referenceSpeedKmh })
        assertTrue(result.motorwayDistanceKm > 0.0)
        result.comparisons.forEach {
            assertTrue(it.modelledEnergyLowKwh < it.modelledEnergyHighKwh)
            assertTrue(it.energyDeltaLowKwh <= it.energyDeltaHighKwh)
            assertTrue(it.rangeDeltaLowKm <= it.rangeDeltaHighKm)
        }
    }

    @Test fun `missing or mismatched evidence never produces a number`() {
        assertEquals(
            SpeedWhatIfUnavailable.MODEL_NOT_TRAINED,
            (SpeedWhatIfCalculator.calculate(trip(), null) as SpeedWhatIfResult.Unavailable).reason,
        )
        val mismatch = trip().copy(
            summary = trip().summary.copy(
                batteryPowerEvidence = evidence.copy(conversionVersion = 2),
            ),
        )
        assertEquals(
            SpeedWhatIfUnavailable.MODEL_NOT_TRAINED,
            (SpeedWhatIfCalculator.calculate(mismatch, model())
                as SpeedWhatIfResult.Unavailable).reason,
        )
    }

    @Test fun `no motorway and incomplete motorway evidence explain unavailability`() {
        val urban = trip(speedKmh = 80f)
        assertEquals(
            SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION,
            (SpeedWhatIfCalculator.calculate(urban, model())
                as SpeedWhatIfResult.Unavailable).reason,
        )
        val missingPower = trip().copy(
            samples = trip().samples?.map { it.copy(batteryPowerKw = null) },
        )
        assertEquals(
            SpeedWhatIfUnavailable.MOTORWAY_ENERGY_UNAVAILABLE,
            (SpeedWhatIfCalculator.calculate(missingPower, model())
                as SpeedWhatIfResult.Unavailable).reason,
        )
    }

    private fun model() = EnergyModel(
        evidence = evidence,
        rollingKwhPer100Km = 10.0,
        aeroKwhPer100KmPerSpeedSquared = 0.0005,
        thermalKwhPer100KmPerDegree = 0.1,
        residualRmseKwhPer100Km = 1.0,
        sampleCount = 100,
        envelope = EnergyModelEnvelope(95.0, 115.0, 0.0, 20.0),
    )

    private fun trip(speedKmh: Float = 100f): StoredTrip = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = 1L,
            endedAtMs = 60_001L,
            durationMs = 60_000L,
            distanceKm = 5.0,
            startSocPercent = 80f,
            endSocPercent = 78f,
            consumedKwh = 1.0,
            regeneratedKwh = 0.0,
            distanceAvailable = true,
            batteryPowerEvidence = evidence,
        ),
        samples = listOf(sample(1L, speedKmh), sample(60_001L, speedKmh)),
    )

    private fun sample(atMs: Long, speedKmh: Float) = TripSample(
        atMs = atMs,
        speedKmh = speedKmh,
        batteryPowerKw = 20f,
        socPercent = 80f,
        outsideTempCelsius = 10f,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = null,
        climateAcOn = null,
        climateFanLevel = null,
    )
}
