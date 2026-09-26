package com.evsuite.chargepilot

import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.ceil
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
            val firstTrip = trip(startedAtMs = 1L, kilometres = 12)
            val first = checkNotNull(
                LocalEnergyModel.loadOrTrain(directory, listOf(firstTrip), evidence)
            )
            // One sample per kilometre window: twelve is the least a model is trained from.
            assertEquals(12, first.sampleCount)

            val secondTrip = trip(startedAtMs = 2L, kilometres = 18)
            val refreshed = checkNotNull(
                LocalEnergyModel.loadOrTrain(
                    directory,
                    listOf(secondTrip, firstTrip),
                    evidence,
                )
            )
            assertEquals(30, refreshed.sampleCount)

            val fallback = checkNotNull(
                LocalEnergyModel.loadOrTrain(directory, emptyList(), evidence)
            )
            assertEquals(30, fallback.sampleCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** [kilometres] steady kilometres, six speeds and two temperatures, a hole after each. */
    private fun trip(startedAtMs: Long, kilometres: Int): StoredTrip {
        var atMs = startedAtMs
        val samples = (0 until kilometres).flatMap { km ->
            val speed = 30.0 + km % 6 * 10.0
            val temperature = if ((km / 6) % 2 == 0) 10.0 else 20.0
            val consumption = 10.0 + 0.0005 * speed * speed +
                0.1 * abs(temperature - 20.0)
            // One interval past the kilometre, so rounding cannot leave the window short.
            val count = ceil(3_600.0 / (5.0 * speed)).toInt() + 2
            List(count) { index ->
                TripSample(
                    atMs = atMs + index * 5_000L,
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
            }.also { atMs += count * 5_000L + 600_000L }
        }
        return StoredTrip(
            summary = EnergyTripSummary(
                startedAtMs = startedAtMs,
                endedAtMs = atMs,
                durationMs = atMs - startedAtMs,
                distanceKm = kilometres.toDouble(),
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
