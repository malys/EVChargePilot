package com.evsuite.chargepilot.route

import com.evsuite.chargepilot.SpeedWhatIfUnavailable
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.model.EnergyModelEnvelope
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWhatIfTest {

    private val pack = BatteryCapacityConfig(61.7, 100.0)

    // Percent of charge per 100 km, which is what CP-052 fits: about 13 % at 90 km/h and
    // 18 % at 130, near enough to an MG4 on a mild day.
    private val model = SocConsumptionModel(
        speedEvidence = VehicleSpeedEvidence(
            FirmwareInfo.Gen.SWI68,
            VehicleSpeedEvidence.CURRENT,
        ),
        rollingPercentPer100Km = 8.0,
        aeroPercentPer100KmPerSpeedSquared = 0.0006,
        thermalPercentPer100KmPerDegree = 0.1,
        residualRmsePercentPer100Km = 0.5,
        segmentCount = 200,
        envelope = EnergyModelEnvelope(60.0, 140.0, -10.0, 40.0),
    )

    /** A section driven at [speedKmh] for [distanceKm], as the router would report it. */
    private fun section(distanceKm: Double, speedKmh: Double, road: String? = "A7") =
        OrsDirections.Section(distanceKm, distanceKm / speedKmh * 60.0, road)

    @Test
    fun `slowing on a motorway saves charge and costs minutes, both of them stated`() {
        val result = RouteWhatIf.slower(
            listOf(section(200.0, 130.0)),
            model,
            outsideTempCelsius = 20.0,
        )
        assertTrue(result is RouteWhatIf.Result.Ready)
        val options = (result as RouteWhatIf.Result.Ready).options
        // 130 is absent: there is nothing on this route to slow down from at 130.
        assertEquals(listOf(120, 110, 100, 90), options.map { it.speedKmh })

        val at100 = options.first { it.speedKmh == 100 }
        // 200 km at 130 takes 92,3 min; at 100 it takes 120. Half an hour, and the driver is
        // told so in the same breath as the saving.
        assertEquals(27.7, at100.delayMinutes, 0.2)
        assertEquals(200.0, at100.affectedKm, 1e-9)
        assertTrue("slower saves charge", at100.savedPercentHigh > 0.0)
        assertTrue("and the band is not a point", at100.savedPercentHigh > at100.savedPercentLow)
        assertTrue(
            "slower saves more",
            options.first { it.speedKmh == 90 }.savedPercentHigh > at100.savedPercentHigh,
        )
    }

    @Test
    fun `a road already driven at 80 is not a road where slowing down is available`() {
        val result = RouteWhatIf.slower(
            listOf(section(300.0, 80.0, "D986")),
            model,
            outsideTempCelsius = 20.0,
        )
        assertEquals(
            RouteWhatIf.Result.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION),
            result,
        )
    }

    @Test
    fun `only the fast part of a mixed route is offered, not the whole of it`() {
        val result = RouteWhatIf.slower(
            listOf(section(100.0, 130.0), section(80.0, 75.0, "D6")),
            model,
            outsideTempCelsius = 20.0,
        ) as RouteWhatIf.Result.Ready
        assertEquals(100.0, result.options.first { it.speedKmh == 110 }.affectedKm, 1e-9)
    }

    @Test
    fun `the headline is the mildest slowdown that removes the stop, not the deepest`() {
        val result = RouteWhatIf.slower(
            listOf(section(300.0, 130.0)),
            model,
            outsideTempCelsius = 20.0,
            // A stop disappears once about 4 points of charge are freed.
            removesStop = { saved -> saved >= 4.0 },
        ) as RouteWhatIf.Result.Ready

        val headline = result.headline
        assertNotNull(headline)
        assertTrue("removes the stop", headline!!.removesStop)
        val faster = result.options.filter { it.speedKmh > headline.speedKmh }
        assertTrue("nothing faster would have done", faster.none { it.removesStop })
    }

    @Test
    fun `no stop to remove means no headline rather than an invented one`() {
        val result = RouteWhatIf.slower(
            listOf(section(200.0, 130.0)),
            model,
            outsideTempCelsius = 20.0,
        ) as RouteWhatIf.Result.Ready
        assertNull(result.headline)
    }

    @Test
    fun `outside the trained envelope, and without a model, it refuses visibly`() {
        assertEquals(
            RouteWhatIf.Result.Unavailable(SpeedWhatIfUnavailable.MODEL_NOT_TRAINED),
            RouteWhatIf.slower(listOf(section(200.0, 130.0)), null, 20.0),
        )
        assertEquals(
            RouteWhatIf.Result.Unavailable(
                SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE,
            ),
            RouteWhatIf.slower(listOf(section(200.0, 130.0)), model, null),
        )
        // 35 °C is inside this fit; -30 °C is a winter the model has never seen.
        assertEquals(
            RouteWhatIf.Result.Unavailable(
                SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE,
            ),
            RouteWhatIf.slower(listOf(section(200.0, 130.0)), model, -30.0),
        )
    }

    @Test
    fun `an alternative road reports what it saves and what it costs, together`() {
        val rate = SocRate(0.4, 0.005, SocRate.Source.TRIP_HISTORY, sampleCount = 8)
        val planned = route(distanceKm = 300.0, durationMinutes = 180.0)
        val longer = route(distanceKm = 330.0, durationMinutes = 240.0, road = "D986")

        val alternative = RouteWhatIf.alternative(planned, longer, rate, pack)!!
        assertEquals("D986", alternative.viaLabel)
        assertEquals(30.0, alternative.distanceDeltaKm, 1e-9)
        assertEquals(60.0, alternative.delayMinutes, 1e-9)
        // Longer at the same rate costs charge rather than saving it, and says so.
        assertTrue(alternative.savedPercentHigh < 0.0)

        assertNull(RouteWhatIf.alternative(planned, longer, null, pack))
    }

    private fun route(distanceKm: Double, durationMinutes: Double, road: String = "A7") =
        OrsDirections.Route(
            distanceKm = distanceKm,
            durationMinutes = durationMinutes,
            points = listOf(
                OrsDirections.Point(4.0, 45.0, null),
                OrsDirections.Point(4.0, 45.5, null),
            ),
            attribution = null,
            sections = listOf(
                OrsDirections.Section(distanceKm, durationMinutes, road),
            ),
        )
}
