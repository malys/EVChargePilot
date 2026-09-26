package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.EcoBand
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import com.evsuite.hardware.telemetry.model.AttributedEnergyEstimate
import com.evsuite.hardware.telemetry.model.EnergyAttribution
import com.evsuite.hardware.telemetry.model.EnergyAttributionResult
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import com.evsuite.hardware.telemetry.model.ResidualAttribution
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoTripReviewTest {

    private val generation = FirmwareInfo.Gen.SWI68
    private val evidence = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.CURRENT)

    /** Predicts 18.0 % per 100 km at 100 km/h and 20 °C, with a band of 0.98. */
    private val model = SocConsumptionModel(
        speedEvidence = evidence,
        rollingPercentPer100Km = 10.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0008,
        thermalPercentPer100KmPerDegree = 0.2,
        residualRmsePercentPer100Km = 0.5,
        segmentCount = SocConsumptionFitter.MIN_SEGMENTS,
        envelope = EnergyModelEnvelope(20.0, 160.0, -10.0, 35.0),
    )

    /** 50 km in half an hour at a steady 100 km/h, with a track to replay. */
    private fun trip(endSoc: Float = 81f, samples: List<TripSample>? = track()) = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = 0L,
            endedAtMs = 1_800_000L,
            durationMs = 1_800_000L,
            distanceKm = 50.0,
            startSocPercent = 90f,
            endSocPercent = endSoc,
            consumedKwh = 9.0,
            regeneratedKwh = 0.5,
            distanceAvailable = true,
            speedEvidence = evidence,
        ),
        samples = samples,
    )

    private fun track(speedKmh: Float = 100f) = (0..1_800).map { second ->
        sample(second * 1_000L, speedKmh)
    }

    /** A drive spent starting and stopping: the steadiness finding has something to measure. */
    private fun harshTrack(): List<TripSample> {
        val samples = ArrayList<TripSample>()
        var atMs = 0L
        var speed = 0f
        repeat(50) {
            repeat(6) { speed += 8f; samples += sample(atMs, speed); atMs += 1_000L }
            repeat(18) { samples += sample(atMs, speed); atMs += 1_000L }
            speed = 0f
        }
        return samples
    }

    private fun sample(atMs: Long, speedKmh: Float) = TripSample(
        atMs = atMs,
        speedKmh = speedKmh,
        batteryPowerKw = null,
        socPercent = null,
        outsideTempCelsius = 20f,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = null,
        climateAcOn = null,
        climateFanLevel = null,
    )

    private fun whatIf(saving: Double = 1.2) = SpeedWhatIfResult.Ready(
        motorwayDistanceKm = 40.0,
        comparisons = listOf(
            comparison(110, saving / 2.0),
            comparison(100, saving),
        ),
    )

    private fun comparison(speedKmh: Int, saving: Double) = SpeedWhatIfComparison(
        referenceSpeedKmh = speedKmh,
        modelledLow = 7.0,
        modelledHigh = 8.0,
        deltaLow = saving,
        deltaHigh = saving + 0.4,
        rangeDeltaLowKm = 4.0,
        rangeDeltaHighKm = 6.0,
    )

    private fun attribution(
        climateKwh: Double = 2.0,
        context: ResidualContext = ResidualContext.CLIMATE_ACTIVE,
        finding: ResidualFinding = ResidualFinding.DISTINGUISHABLE,
    ) = EnergyAttributionResult.Ready(
        EnergyAttribution(
            totalConsumedKwh = Provenanced.derived(9.0),
            modelledTraction = AttributedEnergyEstimate(7.0, 0.5),
            residuals = listOf(
                ResidualAttribution(
                    context = context,
                    estimate = AttributedEnergyEstimate(climateKwh, 0.3),
                    finding = finding,
                    intervalCount = 12,
                    averageFanLevel = 3.0,
                    averageCabinOutsideDeltaCelsius = 4.0,
                )
            ),
            measuredRegenerationKwh = Provenanced.derived(0.5),
            netBatteryEnergyKwh = Provenanced.derived(8.5),
            unmodelledDiscrepancyKwh = Provenanced.derived(0.0),
            reconciliationErrorKwh = 0.0,
        )
    )

    private fun review(
        trip: StoredTrip = trip(),
        socModel: SocConsumptionModel? = model,
        attribution: EnergyAttributionResult? = attribution(),
        whatIf: SpeedWhatIfResult? = whatIf(),
    ) = EcoTripReviewer.review(trip, socModel, attribution, whatIf, generation)

    @Test
    fun `a trip below the fit's expectation reads better than it`() {
        val result = review(trip = trip(endSoc = 83f)) as EcoTripReview.Ready
        assertEquals(EcoBand.BETTER, result.verdict.band.value)
    }

    @Test
    fun `a trip above the fit's expectation reads worse than it`() {
        val result = review(trip = trip(endSoc = 78f)) as EcoTripReview.Ready
        assertEquals(EcoBand.WORSE, result.verdict.band.value)
    }

    @Test
    fun `a refused model still reviews what the other sources measured`() {
        val result = review(socModel = null) as EcoTripReview.Ready
        assertEquals(null, result.verdict.band.value)
        assertEquals(UnavailableReason.MODEL_NOT_TRAINED, result.verdict.band.reason)
        assertTrue(result.findings.any { it is EcoFinding.MotorwaySpeed })
    }

    @Test
    fun `a refused attribution drops the cabin finding and nothing else`() {
        val refused = EnergyAttributionResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        val result = review(attribution = refused) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.Cabin })
        assertTrue(result.findings.any { it is EcoFinding.MotorwaySpeed })
    }

    @Test
    fun `a residual measured with the climate off is not climate energy`() {
        val inactive = attribution(context = ResidualContext.CLIMATE_INACTIVE)
        val result = review(attribution = inactive) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.Cabin })
    }

    @Test
    fun `a residual the fit could not tell from zero is not a finding`() {
        val indistinct = attribution(finding = ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO)
        val result = review(attribution = indistinct) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.Cabin })
    }

    @Test
    fun `a cabin share inside the noise floor is not worth a line`() {
        val small = attribution(climateKwh = 0.2)
        val result = review(attribution = small) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.Cabin })
    }

    @Test
    fun `a refused what-if drops the motorway finding and nothing else`() {
        val refused = SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        val result = review(whatIf = refused) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.MotorwaySpeed })
        assertTrue(result.findings.any { it is EcoFinding.Cabin })
    }

    @Test
    fun `the motorway finding quotes the reference speed that saved the most`() {
        val result = review() as EcoTripReview.Ready
        val motorway = result.findings.filterIsInstance<EcoFinding.MotorwaySpeed>().single()
        assertEquals(100, motorway.referenceSpeedKmh)
        assertEquals(SpeedWhatIfBasis.ENERGY_KWH, motorway.basis)
        assertEquals(40.0, motorway.motorwayDistanceKm, 1e-9)
    }

    @Test
    fun `a trip with no track keeps what the totals measured and refuses the rest`() {
        // The outside temperature lives in the track, so a summary-only trip cannot be asked of
        // the fit at all — and says so, rather than being judged at an assumed 20 degrees.
        val result = review(trip = trip(samples = null)) as EcoTripReview.Ready
        assertTrue(result.findings.none { it is EcoFinding.Steadiness })
        assertEquals(UnavailableReason.SIGNAL_ABSENT, result.verdict.band.reason)
        assertTrue(result.findings.any { it is EcoFinding.MotorwaySpeed })
    }

    @Test
    fun `a whole track is judged, not its last three minutes`() {
        // The live window keeps three minutes; a review that inherited it would grade the driver
        // on their arrival. This drive is harsh throughout and steady for its final stretch.
        val samples = harshTrack() + (1..300).map { sample(1_200_000L + it * 1_000L, 90f) }
        val result = review(trip = trip(samples = samples)) as EcoTripReview.Ready
        assertTrue(result.findings.any { it is EcoFinding.Steadiness })
    }

    @Test
    fun `findings are ordered by how strong their evidence is`() {
        val result = review(trip = trip(samples = harshTrack())) as EcoTripReview.Ready
        assertEquals(
            listOf(
                EcoFinding.MotorwaySpeed::class,
                EcoFinding.Cabin::class,
                EcoFinding.Steadiness::class,
            ),
            result.findings.map { it::class },
        )
        assertTrue(result.findings.size <= EcoTripReviewer.MAX_FINDINGS)
    }

    @Test
    fun `nothing measurable is a stated reason rather than an empty review`() {
        val result = review(
            trip = trip(endSoc = 89f, samples = null),
            socModel = null,
            attribution = null,
            whatIf = null,
        )
        assertEquals(
            EcoTripReview.Unavailable(UnavailableReason.MODEL_NOT_TRAINED),
            result,
        )
    }

    @Test
    fun `a calm drive is reviewed even before any model is trained`() {
        val result = review(
            trip = trip(endSoc = 89f, samples = track(speedKmh = 30f)),
            socModel = null,
            attribution = null,
            whatIf = null,
        ) as EcoTripReview.Ready
        assertEquals(listOf(EcoFinding.Steadiness(0.0)), result.findings)
    }

    @Test
    fun `describe carries the verdict and every finding it kept`() {
        val lines = EcoTripReviewer.describe(review(trip = trip(samples = harshTrack())))
        assertTrue(lines.any { it == "band=TYPICAL" })
        assertTrue(lines.any { it.startsWith("finding=MOTORWAY_SPEED") })
        assertTrue(lines.any { it.startsWith("finding=CABIN") })
        assertTrue(lines.any { it.startsWith("finding=STEADINESS") })
    }

    @Test
    fun `an unavailable review describes its refusal`() {
        val lines = EcoTripReviewer.describe(
            EcoTripReview.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        )
        assertEquals(listOf("review=unavailable(INSUFFICIENT_SAMPLES)"), lines)
    }
}
