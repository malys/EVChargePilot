package com.evsuite.chargepilot

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.model.EnergyModel
import com.evsuite.hardware.telemetry.model.EnergyModelStore
import com.evsuite.hardware.telemetry.model.EnergyModelTrainer
import com.evsuite.hardware.telemetry.model.EnergyModelTrainingResult
import java.io.File

/** Disk-thread-only access to the one bounded model file. */
object LocalEnergyModel {
    fun loadOrTrain(
        filesDir: File,
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence?,
    ): EnergyModel? {
        val store = EnergyModelStore(File(filesDir, MODEL_FILE))
        val stored = store.read()?.takeIf { it.evidence == evidence }
        if (stored != null || evidence == null) return stored
        val trained = EnergyModelTrainer().train(trips, evidence.firmware)
        if (trained !is EnergyModelTrainingResult.Ready) return null
        store.write(trained.model)
        return trained.model
    }

    private const val MODEL_FILE = "energy-model.json"
}
