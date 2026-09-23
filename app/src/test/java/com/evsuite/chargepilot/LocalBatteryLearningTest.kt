package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryLedgerEntry
import com.evsuite.hardware.telemetry.BatteryLedgerStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalBatteryLearningTest {
    @Test
    fun `growing ledger refreshes derived charge sessions`() {
        val directory = Files.createTempDirectory("chargepilot-battery-learning").toFile()
        try {
            val store = BatteryLedgerStore(
                directory.resolve(BatteryLedgerStore.FILE_NAME)
            )
            store.append(entry(0L, 40f))
            store.append(entry(HOUR, 50f))

            val first = LocalBatteryLearning.refresh(directory, 61.7, nowMs = HOUR)
            assertEquals(2, first.entryCount)
            assertEquals(1, first.charge.charges.size)

            store.append(entry(2 * HOUR, 45f))
            store.append(entry(3 * HOUR, 55f))

            val refreshed = LocalBatteryLearning.refresh(directory, 61.7, nowMs = 3 * HOUR)
            assertEquals(4, refreshed.entryCount)
            assertEquals(2, refreshed.charge.charges.size)
            assertSame(refreshed, LocalBatteryLearning.latest)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun entry(atMs: Long, socPercent: Float) = BatteryLedgerEntry(
        atMs = atMs,
        socPercent = socPercent,
        odometerKm = 10_000f,
        outsideTempCelsius = 20f,
        chargingStatus = 1,
        chargePortConnected = true,
        parked = true,
    )

    private companion object {
        const val HOUR = 3_600_000L
    }
}
