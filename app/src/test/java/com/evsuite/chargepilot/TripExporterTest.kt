package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripExporterTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("trip-export-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun csvKeepsMissingPowerAsEmptyCells() {
        val trip = trip(
            startedAtMs = 1_700_000_000_000L,
            consumedKwh = null,
            regeneratedKwh = null,
        )

        val exported = exporter().export(listOf(trip), TripExporter.Format.CSV, true).getOrThrow()
        val cells = exported.file.readLines()[1].split(",", ignoreCase = false, limit = 10)

        assertEquals("12.5", cells[3])
        assertEquals("", cells[6])
        assertEquals("", cells[7])
        assertEquals("", cells[8])
        assertFalse(exported.file.readText().contains(",0,0,0"))
    }

    @Test
    fun csvKeepsUnavailableDistanceEmpty() {
        val trip = trip(startedAtMs = 1L, distanceAvailable = false)

        val exported = exporter().export(listOf(trip), TripExporter.Format.CSV, true).getOrThrow()
        val cells = exported.file.readLines()[1].split(",", ignoreCase = false, limit = 10)

        assertEquals("", cells[3])
        assertEquals("", cells[8])
    }

    @Test
    fun jsonIncludesTrackAndExplicitNulls() {
        val trip = trip(
            startedAtMs = 1L,
            consumedKwh = null,
            regeneratedKwh = null,
            samples = listOf(sample(atMs = 2L)),
        )

        val exported = exporter().export(listOf(trip), TripExporter.Format.JSON, true).getOrThrow()
        val document = JsonParser.parseString(exported.file.readText()).asJsonObject
        val exportedTrip = document.getAsJsonArray("trips")[0].asJsonObject
        val exportedSummary = exportedTrip.getAsJsonObject("summary")
        val exportedSample = exportedTrip.getAsJsonArray("samples")[0].asJsonObject

        assertEquals(1, document.get("schemaVersion").asInt)
        assertEquals("2023-11-14T22:13:20Z", document.get("exportedAtUtc").asString)
        assertEquals(setOf("schemaVersion", "exportedAtUtc", "trips"), document.keySet())
        assertEquals(
            setOf(
                "startedAtMs", "endedAtMs", "durationMs", "distanceKm",
                "startSocPercent", "endSocPercent", "consumedKwh", "regeneratedKwh",
                "distanceAvailable",
            ),
            exportedSummary.keySet(),
        )
        assertEquals(
            setOf(
                "atMs", "speedKmh", "batteryPowerKw", "socPercent", "outsideTempCelsius",
                "cabinTempCelsius", "batteryTempCelsius", "climatePowerOn", "climateAcOn",
                "climateFanLevel",
            ),
            exportedSample.keySet(),
        )
        assertTrue(exportedSummary.get("consumedKwh").isJsonNull)
        assertTrue(exportedSample.get("speedKmh").isJsonNull)
        assertEquals(2L, exportedSample.get("atMs").asLong)
    }

    @Test
    fun exportsTheFullTwoHundredTripLedgerWithinTheBound() {
        val trips = (0 until TripExporter.MAX_TRIPS).map { index ->
            trip(
                startedAtMs = 1_700_000_000_000L + index * 60_000L,
                samples = listOf(sample(atMs = index.toLong())),
            )
        }

        val exported = exporter().export(trips, TripExporter.Format.JSON, false).getOrThrow()

        assertEquals(TripExporter.MAX_TRIPS, exported.tripCount)
        assertTrue(exported.file.length() in 1..TripExporter.MAX_EXPORT_BYTES.toLong())
        assertTrue(root.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun oversizedExportLeavesNoTargetOrTemporaryFile() {
        val result = TripExporter(root, nowMs = { 1L }, maxBytes = 16)
            .export(listOf(trip(startedAtMs = 1L)), TripExporter.Format.JSON, true)

        assertTrue(result.isFailure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun repeatedExportsRetainOnlyTheNewestBoundedSet() {
        val exporter = exporter()
        repeat(TripExporter.MAX_EXPORT_FILES + 2) {
            exporter.export(listOf(trip(startedAtMs = it.toLong())), TripExporter.Format.CSV, true)
                .getOrThrow()
        }

        assertEquals(TripExporter.MAX_EXPORT_FILES, root.listFiles().orEmpty().size)
        assertTrue(root.listFiles().orEmpty().all { it.extension == "csv" })
    }

    private fun exporter() = TripExporter(root, nowMs = { 1_700_000_000_000L })

    private fun trip(
        startedAtMs: Long,
        consumedKwh: Double? = 2.5,
        regeneratedKwh: Double? = 0.25,
        distanceAvailable: Boolean? = true,
        samples: List<TripSample>? = null,
    ) = StoredTrip(
        summary = EnergyTripSummary(
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + 3_600_000L,
            durationMs = 3_600_000L,
            distanceKm = 12.5,
            startSocPercent = 80f,
            endSocPercent = 75.5f,
            consumedKwh = consumedKwh,
            regeneratedKwh = regeneratedKwh,
            distanceAvailable = distanceAvailable,
        ),
        samples = samples,
    )

    private fun sample(atMs: Long) = TripSample(
        atMs = atMs,
        speedKmh = null,
        batteryPowerKw = 12.5f,
        socPercent = 80f,
        outsideTempCelsius = null,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        climatePowerOn = false,
        climateAcOn = null,
        climateFanLevel = null,
    )
}
