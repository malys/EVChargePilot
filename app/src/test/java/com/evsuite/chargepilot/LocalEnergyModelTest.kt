package com.evsuite.chargepilot

import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import java.nio.file.Files
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalEnergyModelTest {
    private val evidence = checkNotNull(
        CarPropertyEvidence.powerModelEvidence(FirmwareInfo.Gen.SWI68)
    )

    @Test
    fun `new bounded history replaces model and failed fit keeps last good model`() {
        val directory = Files.createTempDirectory("chargepilot-local-model").toFile()
        try {
            val firstTrip = trip(startedAtMs = 1L, sampleCount = 60)
            val first = checkNotNull(
                LocalEnergyModel.loadOrTrain(directory, listOf(firstTrip), evidence)
            )
            assertEquals(60, first.sampleCount)

            val secondTrip = trip(startedAtMs = 2L, sampleCount = 80)
            val refreshed = checkNotNull(
                LocalEnergyModel.loadOrTrain(
                    directory,
                    listOf(secondTrip, firstTrip),
                    evidence,
                )
            )
            assertEquals(140, refreshed.sampleCount)

            val fallback = checkNotNull(
                LocalEnergyModel.loadOrTrain(directory, emptyList(), evidence)
            )
            assertEquals(140, fallback.sampleCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun trip(startedAtMs: Long, sampleCount: Int): StoredTrip {
        val samples = List(sampleCount) { index ->
            val speed = 30.0 + index % 6 * 10.0
            val temperature = if ((index / 6) % 2 == 0) 10.0 else 20.0
            val consumption = 10.0 + 0.0005 * speed * speed +
                0.1 * abs(temperature - 20.0)
            TripSample(
                atMs = startedAtMs + index * 5_000L,
                speedKmh = speed.toFloat(),
                batteryPowerKw = (consumption * speed / 100.0).toFloat(),
                socPercent = null,
                outsideTempCelsius = temperature.toFloat(),
                cabinTempCelsius = null,
                batteryTempCelsius = null,
                climatePowerOn = null,
                climateAcOn = null,
                climateFanLevel = null,
            )
        }
        return StoredTrip(
            summary = EnergyTripSummary(
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs + sampleCount * 5_000L,
                durationMs = sampleCount * 5_000L,
                distanceKm = 10.0,
                startSocPercent = 80f,
                endSocPercent = 78f,
                consumedKwh = 2.0,
                regeneratedKwh = 0.0,
                distanceAvailable = true,
                batteryPowerEvidence = evidence,
            ),
            samples = samples,
        )
    }
}
