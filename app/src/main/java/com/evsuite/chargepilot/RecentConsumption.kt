package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.StoredTrip
import java.io.File

/**
 * The last [windowKm] of driving, drawn the way the trip screen draws it: kept trips read once,
 * the drive in progress added on every read. The line is the glance, the average the figure.
 * Shared by the energy dashboard and the split screen so the two can never disagree.
 */
class RecentConsumption(private val context: Context, private val windowKm: Double = WINDOW_KM) {
    data class Reading(
        val trace: List<Float?>,
        val consumptionKwhPer100Km: Double?,
        val coveredKm: Double,
        val referenceKwhPer100Km: Double,
    )

    @Volatile private var stored: List<StoredTrip>? = null

    /** Forget the kept trips, after a save added one. */
    fun invalidate() {
        stored = null
    }

    /** Reads the history file on first use: call off the main thread. */
    fun read(nowMs: Long): Reading {
        val kept = stored ?: EnergyTripHistoryStore(File(context.filesDir, HISTORY_FILE)).read()
            .also { stored = it }
        // With its samples: a summary alone spreads the whole drive's average over the window.
        val current = EnergyTripSession.currentTrip(nowMs)
        val window = TripOverview.window(listOfNotNull(current) + kept, windowKm)
        return Reading(
            trace = TripOverview.trace(window),
            consumptionKwhPer100Km = TripOverview.consumption(window),
            coveredKm = window.sumOf { it.distanceKm },
            referenceKwhPer100Km = VehicleSettings.read(context).referenceConsumptionKwhPer100Km,
        )
    }

    companion object {
        const val WINDOW_KM = 15.0
        private const val HISTORY_FILE = "trips.json"
    }
}
