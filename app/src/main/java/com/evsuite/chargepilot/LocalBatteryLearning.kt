package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryExposure
import com.evsuite.hardware.telemetry.BatteryExposureReport
import com.evsuite.hardware.telemetry.BatteryLedgerStore
import com.evsuite.hardware.telemetry.CalibrationDrift
import com.evsuite.hardware.telemetry.CalibrationDriftReport
import com.evsuite.hardware.telemetry.ChargeEnergyAnalyzer
import com.evsuite.hardware.telemetry.ChargeEnergyReport
import com.evsuite.hardware.telemetry.StateOfHealthEstimator
import com.evsuite.hardware.telemetry.StateOfHealthResult
import java.io.File

/** Existing battery analyses evaluated together from one bounded ledger snapshot. */
internal data class BatteryLearningSnapshot(
    val entryCount: Int,
    val lastEntryAtMs: Long?,
    val health: StateOfHealthResult,
    val drift: CalibrationDriftReport?,
    val exposure: BatteryExposureReport?,
    val charge: ChargeEnergyReport,
)

internal object LocalBatteryLearning {
    @Volatile
    var latest: BatteryLearningSnapshot? = null
        private set

    /** Reads and analyses the ledger. Call only from a worker thread. */
    @Synchronized
    fun refresh(
        filesDir: File,
        usableCapacityKwhWhenNew: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): BatteryLearningSnapshot {
        val entries = BatteryLedgerStore(File(filesDir, BatteryLedgerStore.FILE_NAME)).read()
        val health = StateOfHealthEstimator().estimate(entries, usableCapacityKwhWhenNew)
        val ready = (health as? StateOfHealthResult.Ready)?.estimate
        val exposure = BatteryExposure().analyse(entries)
        return BatteryLearningSnapshot(
            entryCount = entries.size,
            lastEntryAtMs = entries.lastOrNull()?.atMs,
            health = health,
            drift = CalibrationDrift().analyse(entries, nowMs, ready),
            exposure = exposure,
            charge = ChargeEnergyAnalyzer().analyse(entries, exposure?.sessions.orEmpty()),
        ).also { latest = it }
    }
}
