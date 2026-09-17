package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryLedgerStore
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.StateOfHealthEstimator
import com.evsuite.hardware.telemetry.StateOfHealthResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeded files are only worth seeding if the app can read them: a store quarantines an
 * envelope it does not recognise, and a quarantined fixture looks exactly like no fixture at all.
 * A ledger the health estimator refuses looks the same way — the screen says "no health figure
 * yet" whether the file is missing, malformed or simply too thin to measure. Both are read here
 * the way the app reads them, so a schema or threshold change fails in this test rather than on
 * an emulator nobody is watching.
 */
class SeededFixturesTest {
    @Test
    fun `the seeded trips parse as the app reads them`() {
        val fixture = File("../evchargepilot-trips.json")
        assertTrue("fixture missing: ${fixture.absolutePath}", fixture.exists())
        val trips = EnergyTripHistoryStore(fixture).read()
        assertEquals(2, trips.size)
        val drive = trips.first()
        assertEquals(38.2, drive.summary.distanceKm, 0.001)
        // The plot screen needs the samples; the ledger alone would render without them.
        assertTrue("the long trip carries no samples", (drive.samples?.size ?: 0) > 50)
    }

    @Test
    fun `the seeded ledger measures a state of health`() {
        val fixture = File("../evchargepilot-battery-ledger.json")
        assertTrue("fixture missing: ${fixture.absolutePath}", fixture.exists())
        val entries = BatteryLedgerStore(fixture).read()
        val result = StateOfHealthEstimator().estimate(entries, USABLE_CAPACITY_WHEN_NEW_KWH)
        val ready = result as? StateOfHealthResult.Ready
            ?: error("the seeded ledger measures nothing: $result")
        assertEquals(8, ready.estimate.windowCount)
        assertEquals(92.0, checkNotNull(ready.estimate.stateOfHealthPercent.value), 1.5)
    }

    private companion object {
        /** What the seeded settings declare, and what the health figure is a percentage of. */
        const val USABLE_CAPACITY_WHEN_NEW_KWH = 61.7
    }
}
