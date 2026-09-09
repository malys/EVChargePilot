package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeAttributionTest {
    private val speedEvidence =
        VehicleSpeedEvidence(FirmwareInfo.Gen.SWI68, VehicleSpeedEvidence.CURRENT)

    @Test fun `a trip with no power at all is split on the charge gauge`() {
        val attribution = (ChargeAttributionCalculator.calculate(trip(), model())
            as ChargeAttributionResult.Ready).attribution

        assertEquals(100.0, attribution.distanceKm, 1.0)
        assertEquals(20.0, attribution.measuredDropPercent, 0.5)
        // 16 %/100 km over 100 km, which is what the second half spent more than.
        assertEquals(16.0, attribution.modelledDriving.percent, 0.5)
        assertTrue(attribution.modelledDriving.uncertaintyPercent > 0.0)
        // 20 % over 100 km: a point of charge is five kilometres of this trip.
        assertEquals(0.2, attribution.percentPerKm, 0.01)
    }

    @Test fun `the half driven with climate on is distinguishable from the half without`() {
        val residuals = (ChargeAttributionCalculator.calculate(trip(), model())
            as ChargeAttributionResult.Ready).attribution.residuals

        val active = residuals.first { it.context == ResidualContext.CLIMATE_ACTIVE }
        assertEquals(ResidualFinding.DISTINGUISHABLE, active.finding)
        assertTrue("costlier than a typical trip", active.estimate.bandLowPercent > 0.0)
        assertEquals(1, active.runCount)

        // The climate-off half was driven at exactly what the model expects, so the honest
        // answer there is that nothing can be told apart from model error.
        val inactive = residuals.first { it.context == ResidualContext.CLIMATE_INACTIVE }
        assertEquals(ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO, inactive.finding)
    }

    @Test fun `charge spent outside the model's envelope stays in its own row`() {
        val base = trip()
        val lastAtMs = base.samples!!.last().atMs
        val lastSoc = base.samples!!.last().socPercent!!
        // Ten minutes at 150 km/h: a speed the fit never saw, so it cannot be attributed.
        val beyond = (1..10).map { minute ->
            sample(
                atMs = lastAtMs + minute * STEP_MS,
                speedKmh = 150f,
                socPercent = lastSoc - minute * 0.6f,
                climateOn = true,
            )
        }
        val attribution = (ChargeAttributionCalculator
            .calculate(base.copy(samples = base.samples!! + beyond), model())
            as ChargeAttributionResult.Ready).attribution

        assertEquals(6.0, attribution.uncoveredDropPercent, 0.5)
        assertEquals(100.0, attribution.distanceKm, 1.0)
    }

    @Test fun `a gauge that barely moved is refused rather than divided by noise`() {
        val flat = trip().let { trip ->
            trip.copy(samples = trip.samples?.map { it.copy(socPercent = 80f) })
        }
        assertEquals(
            ChargeAttributionUnavailable.CHARGE_DROP_TOO_SMALL,
            (ChargeAttributionCalculator.calculate(flat, model())
                as ChargeAttributionResult.Unavailable).reason,
        )
    }

    @Test fun `a fit trained under another distance conversion is not this trip's fit`() {
        val stale = model().copy(
            speedEvidence = VehicleSpeedEvidence(
                FirmwareInfo.Gen.SWI68,
                VehicleSpeedEvidence.MPS_TIMES_3_6_V1,
            ),
        )
        assertEquals(
            ChargeAttributionUnavailable.MODEL_NOT_TRAINED,
            (ChargeAttributionCalculator.calculate(trip(), stale)
                as ChargeAttributionResult.Unavailable).reason,
        )
        assertEquals(
            ChargeAttributionUnavailable.MODEL_NOT_TRAINED,
            (ChargeAttributionCalculator.calculate(trip(), null)
                as ChargeAttributionResult.Unavailable).reason,
        )
    }

    @Test fun `a trip the gauge never spoke on has nothing to attribute`() {
        val blind = trip().let { trip ->
            trip.copy(samples = trip.samples?.map { it.copy(socPercent = null) })
        }
        assertEquals(
            ChargeAttributionUnavailable.NO_MODELLED_INTERVAL,
            (ChargeAttributionCalculator.calculate(blind, model())
                as ChargeAttributionResult.Unavailable).reason,
        )
    }

    /** 16 %/100 km at 100 km/h and 10 °C, which is what the trip's first half spends. */
    private fun model() = SocConsumptionModel(
        speedEvidence = speedEvidence,
        rollingPercentPer100Km = 10.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0005,
        thermalPercentPer100KmPerDegree = 0.1,
        residualRmsePercentPer100Km = 1.0,
        segmentCount = 100,
        envelope = EnergyModelEnvelope(95.0, 115.0, 0.0, 20.0),
    )

    /**
     * 100 km at 100 km/h: fifty at the model's own rate with the climate off, then fifty at
     * half again as much with it on. 8 % then 12 %, so the second half carries 4 % the model
     * did not expect.
     */
    private fun trip(): StoredTrip {
        val steps = 60
        var soc = 80.0
        val samples = (0..steps).map { index ->
            val climateOn = index > steps / 2
            val current = sample(
                atMs = 1L + index * STEP_MS,
                speedKmh = 100f,
                socPercent = soc.toFloat(),
                climateOn = climateOn,
            )
            // 1,667 km per minute at 16 %/100 km, or at 24 %/100 km with the climate on.
            soc -= (if (climateOn) 24.0 else 16.0) * (100.0 / steps) / 100.0
            current
        }
        return StoredTrip(
            summary = EnergyTripSummary(
                startedAtMs = samples.first().atMs,
                endedAtMs = samples.last().atMs,
                durationMs = samples.last().atMs - samples.first().atMs,
                distanceKm = 100.0,
                startSocPercent = 80f,
                endSocPercent = samples.last().socPercent,
                consumedKwh = null,
                regeneratedKwh = null,
                distanceAvailable = true,
                batteryPowerEvidence = null,
                speedEvidence = speedEvidence,
            ),
            samples = samples,
        )
    }

    private fun sample(
        atMs: Long,
        speedKmh: Float,
        socPercent: Float,
        climateOn: Boolean,
    ) = TripSample(
        atMs = atMs,
        speedKmh = speedKmh,
        batteryPowerKw = null,
        socPercent = socPercent,
        outsideTempCelsius = 10f,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = climateOn,
        climateAcOn = null,
        climateFanLevel = null,
    )

    private companion object {
        const val STEP_MS = 60_000L
    }
}
