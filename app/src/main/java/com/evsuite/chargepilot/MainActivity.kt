package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityMainBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.Provenanced
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var provenance: ProvenanceText
    /** Ten binder reads and a disk read for the diagnostics report; not the main thread's work. */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-diagnostics")
    }

    /** The recorder owns the sampler; this screen is one of its readers. */
    private var recorder: TripRecordingService? = null
    /** The last frame drawn, so the diagnostics report can explain what the screen shows. */
    @Volatile private var latestReadings: DashboardReadings = DashboardReadings.empty()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = bound
            bound.setListener(::render)
            bound.latest?.let(::render)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        provenance = ProvenanceText(this)
        binding.tripAction.setOnClickListener { toggleTrip() }
        binding.historyAction.setOnClickListener {
            startActivity(Intent(this, TripHistoryActivity::class.java))
        }
        binding.diagnosticsAction.setOnClickListener {
            if (EvidenceCaptureHook.IS_SUPPORTED) {
                EvidenceCaptureHook.open(this)
            } else {
                showDiagnostics()
            }
        }
        renderUnavailable()
    }

    /**
     * Binding is what makes the recorder sample for this screen; it keeps sampling after the
     * unbind only when a trip is actually being recorded.
     */
    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        recorder?.setListener(null)
        recorder = null
        unbindService(connection)
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun toggleTrip() {
        val service = recorder
        if (EnergyTripSession.isRecording) {
            if (service == null) {
                AppLogger.w(TAG, "trip stop ignored: recorder not bound")
                return
            }
            service.stopTrip()
            service.latest?.let(::render)
        } else {
            TripRecordingService.start(this)
        }
    }

    private fun render(value: EnergySnapshot) {
        val readings = DashboardReadings.of(value, EnergyTripSession.current(value.timestampMs))
        latestReadings = readings
        renderReadings(readings)
        binding.climateValue.text = climate(value)

        val hasVehicleData = value.hasVehicleData
        binding.dataStatus.text = getString(
            if (hasVehicleData) R.string.status_vehicle_connected else R.string.status_waiting_for_vehicle
        )
        binding.dataStatus.setTextColor(getColor(if (hasVehicleData) R.color.ev_ok else R.color.ev_warn))

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

    private fun renderReadings(readings: DashboardReadings) {
        bind(binding.socValue, R.string.label_soc, readings.soc, PATTERN_SOC, SOC_UNAVAILABLE)
        bind(binding.rangeValue, R.string.label_range, readings.range, PATTERN_DISTANCE)
        bind(binding.speedValue, R.string.label_speed, readings.speed, PATTERN_SPEED)
        bind(binding.powerValue, R.string.label_power, readings.power, PATTERN_POWER, POWER_UNAVAILABLE)
        bind(binding.outsideTempValue, R.string.label_outside_temp, readings.outsideTemp, PATTERN_TEMP)
        bind(binding.batteryTempValue, R.string.label_battery_temp, readings.batteryTemp, PATTERN_TEMP)
        bind(binding.tripDistanceValue, R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE)
        bind(binding.tripEnergyValue, R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY)
        bind(binding.tripRegenValue, R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY)
        bind(
            binding.tripConsumptionValue, R.string.label_consumption,
            readings.tripConsumption, PATTERN_CONSUMPTION,
        )
        val recorded = provenance.renderWith(readings.tripDuration, transform = ::duration)
        binding.tripDurationValue.text = recorded
        binding.tripDurationValue.contentDescription =
            provenance.describe(getString(R.string.label_duration), readings.tripDuration, recorded)
    }

    /**
     * A reading and its description move together: the visible text says what the value is,
     * the description says what kind of claim it is and, when it is missing, why. Colour is
     * deliberately not carrying either — it would say nothing to a screen reader and nothing
     * in daylight.
     */
    private fun bind(
        view: TextView,
        label: Int,
        value: Provenanced<out Any>,
        pattern: String,
        unavailable: String = DASH,
    ) {
        val rendered = provenance.render(value, pattern, unavailable)
        view.text = rendered
        view.contentDescription = provenance.describe(getString(label), value, rendered)
    }

    private fun renderUnavailable() {
        latestReadings = DashboardReadings.empty()
        renderReadings(latestReadings)
        binding.climateValue.text = DASH
        binding.dataStatus.text = getString(R.string.status_waiting_for_vehicle)
        binding.tripAction.isEnabled = false
        binding.historyAction.isEnabled = true
        binding.tripHint.text = getString(R.string.trip_control_speed_unavailable)
    }

    /**
     * The report reads ten vehicle properties over binder and the crash file off disk, so it is
     * built on the sampler thread and only the dialog itself touches the main one.
     */
    private fun showDiagnostics() {
        background.execute {
            val body = diagnosticsReport()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(R.string.diagnostics_title)
                    .setMessage(body)
                    .setPositiveButton(R.string.action_close, null)
                    .show()
            }
        }
    }

    private fun diagnosticsReport(): String {
        val crash = CrashLogger.read(applicationContext)
        val recentLog = AppLogger.entries.takeLast(30).joinToString("\n") {
            "[${it.time}] ${it.level}/${it.tag}: ${it.msg}"
        }
        return buildString {
            appendLine(getString(R.string.diagnostics_firmware, FirmwareInfo.getGeneration().name))
            appendLine(getString(R.string.diagnostics_read_only))
            appendLine()
            if (crash != null) {
                appendLine(getString(R.string.diagnostics_previous_crash))
                appendLine(crash)
                appendLine()
            }
            // A field showing an em dash says the signal is unusable but not why. This says why:
            // unsupported, declared and never published, or unreachable on this runtime.
            appendLine(getString(R.string.diagnostics_properties))
            appendLine(getString(R.string.diagnostics_properties_hint))
            EVHardware.probeTelemetryProperties().forEach { appendLine(it.toString()) }
            appendLine()
            // The dashboard has room for an em dash and not for a sentence. The report is
            // where the sentence goes: per field, what kind of value it is and why it is
            // missing when it is.
            appendLine(getString(R.string.diagnostics_provenance))
            describeReadings(latestReadings).forEach { appendLine(it) }
            appendLine()
            appendLine(getString(R.string.diagnostics_recent_log))
            append(recentLog.ifBlank { getString(R.string.diagnostics_no_log) })
        }
    }

    private fun describeReadings(readings: DashboardReadings): List<String> = listOf(
        Triple(R.string.label_soc, readings.soc, PATTERN_SOC),
        Triple(R.string.label_range, readings.range, PATTERN_DISTANCE),
        Triple(R.string.label_speed, readings.speed, PATTERN_SPEED),
        Triple(R.string.label_power, readings.power, PATTERN_POWER),
        Triple(R.string.label_outside_temp, readings.outsideTemp, PATTERN_TEMP),
        Triple(R.string.label_battery_temp, readings.batteryTemp, PATTERN_TEMP),
        Triple(R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE),
        Triple(R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY),
        Triple(R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY),
        Triple(R.string.label_consumption, readings.tripConsumption, PATTERN_CONSUMPTION),
    ).map { (label, value, pattern) ->
        provenance.describe(getString(label), value, provenance.render(value, pattern))
    }

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
        // The unit-bearing fields keep their unit while unavailable so the layout does not
        // shift the moment the vehicle starts answering.
        const val SOC_UNAVAILABLE = "— %"
        const val POWER_UNAVAILABLE = "— kW"
        const val PATTERN_SOC = "%.1f %%"
        const val PATTERN_SPEED = "%.0f km/h"
        const val PATTERN_POWER = "%+.1f kW"
        const val PATTERN_TEMP = "%.0f °C"
        const val PATTERN_DISTANCE = "%.1f km"
        const val PATTERN_ENERGY = "%.2f kWh"
        const val PATTERN_CONSUMPTION = "%.1f kWh/100 km"
    }
}
