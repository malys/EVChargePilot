package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.telemetry.EcoBand
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoTripAdviceTest {

    private val evidence = VehicleSpeedEvidence(FirmwareInfo.Gen.SWI68, VehicleSpeedEvidence.CURRENT)

    private fun summary(startedAtMs: Long = 0L) = EnergyTripSummary(
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 1_800_000L,
        durationMs = 1_800_000L,
        distanceKm = 50.0,
        startSocPercent = 90f,
        endSocPercent = 80f,
        consumedKwh = 9.0,
        regeneratedKwh = 1.0,
        distanceAvailable = true,
        speedEvidence = evidence,
    )

    private fun sample(atMs: Long, speedKmh: Float, outsideC: Float = 20f) = TripSample(
        atMs = atMs,
        speedKmh = speedKmh,
        batteryPowerKw = null,
        socPercent = null,
        outsideTempCelsius = outsideC,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = null,
        climateAcOn = null,
        climateFanLevel = null,
    )

    private fun steadyTrack() = (0..1_800).map { sample(it * 1_000L, 100f) }

    /** Stop-and-go: 6 s at +8 km/h per second, 18 s steady, then a hard stop. */
    private fun harshTrack(outsideC: Float = 20f): List<TripSample> {
        val samples = ArrayList<TripSample>()
        var atMs = 0L
        var speed = 0f
        repeat(50) {
            repeat(6) { speed += 8f; samples += sample(atMs, speed, outsideC); atMs += 1_000L }
            repeat(18) { samples += sample(atMs, speed, outsideC); atMs += 1_000L }
            speed = 0f
        }
        return samples
    }

    private fun verdict(band: EcoBand, observed: Double = 20.0, expected: Double = 16.0) =
        EcoVerdict(
            band = Provenanced.estimated(band, 1.0),
            observedPercentPer100Km = observed,
            expectedPercentPer100Km = expected,
            deltaPercent = (observed - expected) / expected * 100.0,
            meanSpeedKmh = 100.0,
        )

    private fun ready(band: EcoBand, findings: List<EcoFinding> = emptyList()) =
        EcoTripReview.Ready(verdict(band), findings)

    @Test
    fun `a steady drive has no harsh time and no smoother tip`() {
        val style = DrivingStyle.measure(steadyTrack())
        assertEquals(0L, style.harshMs)
        assertTrue(style.movingMs > 0L)
        assertNull(EcoTripAdvice.smoother(style))
    }

    @Test
    fun `hard acceleration and hard braking are both counted`() {
        val style = DrivingStyle.measure(harshTrack())
        assertTrue(style.harshAccelerationMs > 0L)
        assertTrue(style.harshBrakingMs > 0L)
        val tip = EcoTripAdvice.smoother(style)!!
        assertTrue(tip.sharePercent >= 20)
        assertTrue(tip.harshMinutes >= 4)
    }

    @Test
    fun `a gap in the track is not read as an acceleration`() {
        val style = DrivingStyle.measure(listOf(sample(0L, 0f), sample(60_000L, 120f)))
        assertEquals(DrivingStyle.NONE, style)
    }

    @Test
    fun `a heavier drive can save its excess and no more`() {
        val saving = EcoTripAdvice.saving(verdict(EcoBand.WORSE), summary())!!
        assertEquals(20.0, saving.percent, 1e-9)
        assertEquals(8.0 * 0.2, saving.kwh!!, 1e-9)
        assertEquals(10.0 * 0.2, saving.socPoints!!, 1e-9)
    }

    @Test
    fun `a usual or economical drive has nothing to save`() {
        assertNull(EcoTripAdvice.saving(verdict(EcoBand.TYPICAL), summary()))
        assertNull(EcoTripAdvice.saving(verdict(EcoBand.BETTER, 14.0, 16.0), summary()))
    }

    @Test
    fun `a sharp drive in sport mode is pointed at eco mode`() {
        val advice = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack()), ready(EcoBand.WORSE), DriveMode.SPORT,
        )
        assertTrue(advice.tips.first() is EcoTip.Smoother)
        assertTrue(EcoTip.EcoMode(DriveMode.SPORT) in advice.tips)
        assertTrue(advice.tips.none { it is EcoTip.SnowMode })
        assertEquals(20.0, advice.saving!!.percent, 1e-9)
    }

    @Test
    fun `a driver already in eco mode is not told to choose it`() {
        val advice = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack()), ready(EcoBand.WORSE), DriveMode.ECO,
        )
        assertTrue(advice.tips.none { it is EcoTip.EcoMode })
    }

    @Test
    fun `an unreadable mode still names eco mode, conditionally`() {
        val advice = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack()), ready(EcoBand.TYPICAL), null,
        )
        assertTrue(EcoTip.EcoMode(null) in advice.tips)
        assertNull(advice.saving)
    }

    @Test
    fun `snow mode is named only for a sharp drive in the cold`() {
        val cold = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack(outsideC = 1f)), ready(EcoBand.TYPICAL), DriveMode.NORMAL,
        )
        assertTrue(EcoTip.SnowMode(1) in cold.tips)
        val inSnow = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack(outsideC = 1f)), ready(EcoBand.TYPICAL), DriveMode.SNOW,
        )
        assertTrue(inSnow.tips.none { it is EcoTip.SnowMode || it is EcoTip.EcoMode })
    }

    @Test
    fun `a calm usual drive gets no tip`() {
        val advice = EcoTripAdvice.advise(
            StoredTrip(summary(), steadyTrack()), ready(EcoBand.TYPICAL), DriveMode.NORMAL,
        )
        assertEquals(EcoBand.TYPICAL, advice.band)
        assertTrue(advice.tips.isEmpty())
    }

    @Test
    fun `a refused review keeps the style tip and has no band`() {
        val advice = EcoTripAdvice.advise(
            StoredTrip(summary(), harshTrack()),
            EcoTripReview.Unavailable(UnavailableReason.MODEL_NOT_TRAINED),
            DriveMode.NORMAL,
        )
        assertNull(advice.band)
        assertNull(advice.saving)
        assertTrue(advice.tips.first() is EcoTip.Smoother)
    }

    @Test
    fun `the history adds up heavier trips and names the habits`() {
        val motorway = EcoFinding.MotorwaySpeed(100, 0.8, 1.2, SpeedWhatIfBasis.ENERGY_KWH, 40.0)
        val trips = listOf(
            StoredTrip(summary(3L), harshTrack()),
            StoredTrip(summary(2L), steadyTrack()),
            StoredTrip(summary(1L), steadyTrack()),
        )
        val reviews = mapOf(
            3L to ready(EcoBand.WORSE, listOf(EcoFinding.Cabin(1.0, 0.2, 12.0))),
            2L to ready(EcoBand.WORSE, listOf(motorway, EcoFinding.Cabin(1.0, 0.2, 8.0))),
            1L to ready(EcoBand.TYPICAL, listOf(motorway)),
        )
        val history = EcoTripAdvice.history(trips, reviews)
        assertEquals(3, history.reviewedTrips)
        assertEquals(2, history.heavierTrips)
        assertEquals(2 * 8.0 * 0.2, history.excessKwh!!, 1e-9)
        assertEquals(1.6, history.motorwaySaving!!, 1e-9)
        assertEquals(2, history.motorwayTrips)
        assertEquals(10.0, history.cabinMeanPercent!!, 1e-9)
        assertTrue(history.hasAdvice)
    }

    @Test
    fun `a history with nothing reviewed has no advice`() {
        val history = EcoTripAdvice.history(listOf(StoredTrip(summary(), steadyTrack())), emptyMap())
        assertEquals(0, history.reviewedTrips)
        assertFalse(history.hasAdvice)
    }

    @Test
    fun `the history reads only the newest trips`() {
        val trips = (0 until EcoTripAdvice.HISTORY_TRIPS + 5).map { StoredTrip(summary(it.toLong())) }
        val reviews = trips.associate { it.summary.startedAtMs to ready(EcoBand.WORSE) }
        assertEquals(EcoTripAdvice.HISTORY_TRIPS, EcoTripAdvice.history(trips, reviews).heavierTrips)
    }
}
