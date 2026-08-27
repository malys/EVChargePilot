package com.evsuite.chargepilot

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityMainBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.EnergyTripSummary
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var reader: EnergyTelemetryReader
    private lateinit var tripStore: EnergyTripHistoryStore
    private val sampler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chargepilot-sampler")
    }
    private var samplingTask: ScheduledFuture<*>? = null
    /** Written by the sampler thread, read by the main thread on a control tap. */
    @Volatile private var latest: EnergySnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reader = EnergyTelemetryReader(applicationContext)
        tripStore = EnergyTripHistoryStore(File(filesDir, "trips.json"))
        binding.tripAction.setOnClickListener { toggleTrip() }
        binding.diagnosticsAction.setOnClickListener { showDiagnostics() }
        renderUnavailable()
    }

    override fun onStart() {
        super.onStart()
        samplingTask = sampler.scheduleWithFixedDelay(::sample, 0L, 1L, TimeUnit.SECONDS)
    }

    override fun onStop() {
        samplingTask?.cancel(false)
        samplingTask = null
        super.onStop()
    }

    override fun onDestroy() {
        sampler.shutdownNow()
        super.onDestroy()
    }

    private fun sample() {
        val value = runCatching { reader.read() }
            .onFailure { AppLogger.w(TAG, "telemetry sample failed: ${it.message}") }
            .getOrNull() ?: return
        latest = value
        EnergyTripSession.add(value)
        runOnUiThread { render(value) }
    }

    private fun toggleTrip() {
        val value = latest ?: return
        if (EnergyTripSession.isRecording) {
            val summary = EnergyTripSession.stop(value.timestampMs) ?: return
            if (!tripStore.append(summary)) {
                AppLogger.w(TAG, "trip history could not be saved")
            }
        } else {
            EnergyTripSession.start(value)
        }
        render(value)
    }

    private fun render(value: EnergySnapshot) {
        binding.socValue.text = percent(value.socPercent)
        binding.rangeValue.text = distance(value.rangeKm?.toDouble())
        binding.speedValue.text = speed(value.speedKmh)
        binding.powerValue.text = power(value.batteryPowerKw)
        binding.outsideTempValue.text = temperature(value.outsideTempCelsius)
        binding.batteryTempValue.text = temperature(value.batteryTempCelsius)
        binding.climateValue.text = climate(value)

        val hasVehicleData = value.hasVehicleData
        binding.dataStatus.text = getString(
            if (hasVehicleData) R.string.status_vehicle_connected else R.string.status_waiting_for_vehicle
        )
        binding.dataStatus.setTextColor(getColor(if (hasVehicleData) R.color.ev_ok else R.color.ev_warn))

        val trip = EnergyTripSession.current(value.timestampMs)
        renderTrip(trip)

        // Recording controls are for a parked driver. Live values remain passive and readable
        // while moving; the app never presents an overlay or asks for attention.
        val parked = value.speedKmh?.let { it <= 0.1f } == true
        binding.tripAction.isEnabled = parked
        binding.tripAction.text = getString(
            if (EnergyTripSession.isRecording) R.string.action_stop_trip else R.string.action_start_trip
        )
        binding.tripHint.text = when {
            value.speedKmh == null -> getString(R.string.trip_control_speed_unavailable)
            !parked -> getString(R.string.trip_control_park_to_change)
            EnergyTripSession.isRecording -> getString(R.string.trip_recording)
            else -> getString(R.string.trip_ready)
        }
    }

    private fun renderTrip(summary: EnergyTripSummary?) {
        binding.tripDurationValue.text = summary?.durationMs?.let(::duration) ?: DASH
        binding.tripDistanceValue.text = summary?.distanceKm?.let(::distance) ?: DASH
        binding.tripEnergyValue.text = energy(summary?.consumedKwh)
        binding.tripRegenValue.text = energy(summary?.regeneratedKwh)
        binding.tripConsumptionValue.text = consumption(summary?.averageConsumptionKwhPer100Km)
    }

    private fun renderUnavailable() {
        binding.socValue.text = getString(R.string.value_soc_unavailable)
        binding.rangeValue.text = DASH
        binding.speedValue.text = DASH
        binding.powerValue.text = getString(R.string.value_power_unavailable)
        binding.outsideTempValue.text = DASH
        binding.batteryTempValue.text = DASH
        binding.climateValue.text = DASH
        binding.dataStatus.text = getString(R.string.status_waiting_for_vehicle)
        renderTrip(null)
        binding.tripAction.isEnabled = false
        binding.tripHint.text = getString(R.string.trip_control_speed_unavailable)
    }

    private fun showDiagnostics() {
        val crash = CrashLogger.read(this)
        val recentLog = AppLogger.entries.takeLast(30).joinToString("\n") {
            "[${it.time}] ${it.level}/${it.tag}: ${it.msg}"
        }
        val body = buildString {
            appendLine(getString(R.string.diagnostics_firmware, FirmwareInfo.getGeneration().name))
            appendLine(getString(R.string.diagnostics_read_only))
            appendLine()
            if (crash != null) {
                appendLine(getString(R.string.diagnostics_previous_crash))
                appendLine(crash)
                appendLine()
            }
            appendLine(getString(R.string.diagnostics_recent_log))
            append(recentLog.ifBlank { getString(R.string.diagnostics_no_log) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setMessage(body)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun percent(value: Float?) = value?.let { format("%.1f %%", it) }
        ?: getString(R.string.value_soc_unavailable)
    private fun speed(value: Float?) = value?.let { format("%.0f km/h", it) } ?: DASH
    private fun power(value: Float?) = value?.let { format("%+.1f kW", it) }
        ?: getString(R.string.value_power_unavailable)
    private fun temperature(value: Float?) = value?.let { format("%.0f °C", it) } ?: DASH
    private fun distance(value: Double?) = value?.let { format("%.1f km", it) } ?: DASH
    private fun energy(value: Double?) = value?.let { format("%.2f kWh", it) } ?: DASH
    private fun consumption(value: Double?) = value?.let { format("%.1f kWh/100 km", it) } ?: DASH

    private fun climate(value: EnergySnapshot): String = when (value.climate.powerOn) {
        true -> buildString {
            append(getString(if (value.climate.acOn == true) R.string.climate_ac_on else R.string.climate_on))
            value.climate.fanLevel?.let { append(getString(R.string.climate_fan_level, it)) }
        }
        false -> getString(R.string.climate_off)
        null -> DASH
    }

    private fun duration(milliseconds: Long): String {
        val totalMinutes = milliseconds / 60_000L
        return format("%d:%02d", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.getDefault(), pattern, *args)

    private companion object {
        const val TAG = "EVChargePilot"
        const val DASH = "—"
    }
}
