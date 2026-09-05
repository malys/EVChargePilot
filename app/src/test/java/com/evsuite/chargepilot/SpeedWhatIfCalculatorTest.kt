package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.model.EnergyModel
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
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

        assertEquals(SpeedWhatIfBasis.ENERGY_KWH, result.basis)
        assertEquals(listOf(100, 110), result.comparisons.map { it.referenceSpeedKmh })
        assertTrue(result.motorwayDistanceKm > 0.0)
        result.comparisons.forEach {
            assertTrue(it.modelledLow < it.modelledHigh)
            assertTrue(it.deltaLow <= it.deltaHigh)
            assertTrue(it.rangeDeltaLowKm <= it.rangeDeltaHighKm)
        }
    }

    @Test fun `a trip with no power at all is measured on the charge gauge instead`() {
        val result = SpeedWhatIfCalculator.calculate(socTrip(), null, socModel())
            as SpeedWhatIfResult.Ready

        assertEquals(SpeedWhatIfBasis.STATE_OF_CHARGE_PERCENT, result.basis)
        assertEquals(listOf(100, 110), result.comparisons.map { it.referenceSpeedKmh })
        // 26 % over 100 km at 130 km/h is dearer than this model expects at either reference
        // speed, so both rows report charge that slowing down would have saved.
        result.comparisons.forEach {
            assertTrue("modelled in percent, not kWh", it.modelledLow in 1.0..20.0)
            assertTrue("slower would have saved", it.deltaHigh > 0.0)
            assertTrue(it.rangeDeltaLowKm <= it.rangeDeltaHighKm)
            // 26 % over 100 km is 0,26 %/km, so a point of charge is a little under 4 km.
            assertTrue("distance comes from this trip's own rate", it.rangeDeltaHighKm > 0.0)
        }
    }

    @Test fun `a stretch the gauge barely moved on is refused rather than divided by noise`() {
        val flat = socTrip().let { trip ->
            trip.copy(samples = trip.samples?.map { it.copy(socPercent = 80f) })
        }
        assertEquals(
            SpeedWhatIfUnavailable.MOTORWAY_CHARGE_DROP_TOO_SMALL,
            (SpeedWhatIfCalculator.calculate(flat, null, socModel())
                as SpeedWhatIfResult.Unavailable).reason,
        )
        val blind = socTrip().let { trip ->
            trip.copy(samples = trip.samples?.map { it.copy(socPercent = null) })
        }
        assertEquals(
            SpeedWhatIfUnavailable.MOTORWAY_CHARGE_UNAVAILABLE,
            (SpeedWhatIfCalculator.calculate(blind, null, socModel())
                as SpeedWhatIfResult.Unavailable).reason,
        )
    }

    @Test fun `a fit trained under another distance conversion is not this trip's fit`() {
        val stale = socModel().copy(
            speedEvidence = VehicleSpeedEvidence(
                FirmwareInfo.Gen.SWI68,
                VehicleSpeedEvidence.MPS_TIMES_3_6_V1,
            ),
        )
        assertEquals(
            SpeedWhatIfUnavailable.MODEL_NOT_TRAINED,
            (SpeedWhatIfCalculator.calculate(socTrip(), null, stale)
                as SpeedWhatIfResult.Unavailable).reason,
        )
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

    private val speedEvidence =
        VehicleSpeedEvidence(FirmwareInfo.Gen.SWI68, VehicleSpeedEvidence.CURRENT)

    private fun socModel() = SocConsumptionModel(
        speedEvidence = speedEvidence,
        rollingPercentPer100Km = 10.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0005,
        thermalPercentPer100KmPerDegree = 0.1,
        residualRmsePercentPer100Km = 1.0,
        segmentCount = 100,
        envelope = EnergyModelEnvelope(95.0, 115.0, 0.0, 20.0),
    )

    /** 100 km at 130 km/h, 26 % of charge spent, and not one battery-power reading. */
    private fun socTrip(): StoredTrip {
        val stepMs = 60_000L
        val steps = (100.0 / 130.0 * 3_600_000.0 / stepMs).toInt()
        val samples = (0..steps).map { index ->
            sample(1L + index * stepMs, 130f).copy(
                batteryPowerKw = null,
                socPercent = (80.0 - 26.0 * index / steps).toFloat(),
            )
        }
        return StoredTrip(
            summary = EnergyTripSummary(
                startedAtMs = samples.first().atMs,
                endedAtMs = samples.last().atMs,
                durationMs = samples.last().atMs - samples.first().atMs,
                distanceKm = 100.0,
                startSocPercent = 80f,
                endSocPercent = 54f,
                consumedKwh = null,
                regeneratedKwh = null,
                distanceAvailable = true,
                batteryPowerEvidence = null,
                speedEvidence = speedEvidence,
            ),
            samples = samples,
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
