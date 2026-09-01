package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityMainBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.hardware.telemetry.AdaptiveRangeEstimator
import com.evsuite.hardware.telemetry.ConsumptionCalculator
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.TripDetector
import com.evsuite.hardware.telemetry.UnavailableReason
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var provenance: ProvenanceText
    private val consumption = ConsumptionCalculator()
    private val rangeEstimator = AdaptiveRangeEstimator()
    /** Bounded history parsing and diagnostics I/O never run on the main thread. */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-background")
    }

    /** The recorder owns the sampler; this screen is one of its readers. */
    private var recorder: TripRecordingService? = null
    /** The last frame drawn, so the diagnostics report can explain what the screen shows. */
    @Volatile private var latestReadings: DashboardReadings = DashboardReadings.empty()
    /** Immutable snapshot loaded off the UI thread; the store writes newest trips first. */
    @Volatile private var recentTrips: List<EnergyTripSummary> = emptyList()
    /** Rebuilt only when history or firmware evidence changes, never on every 1 Hz frame. */
    private var trustedTripsSource: List<EnergyTripSummary> = emptyList()
    private var trustedTripsEvidence: BatteryPowerEvidence? = null
    private var trustedTripsCache: List<EnergyTripSummary> = emptyList()
    private var updatingAutomaticSwitch = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = bound
            setAutomaticSwitchChecked(bound.automaticDetectionEnabled)
            bound.setListener(this@MainActivity, ::render)
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
        setAutomaticSwitchChecked(TripRecordingService.isAutomaticDetectionEnabled(this))
        binding.automaticDetection.setOnCheckedChangeListener { _, enabled ->
            if (!updatingAutomaticSwitch) changeAutomaticDetection(enabled)
        }
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
        if (TripRecordingService.isAutomaticDetectionEnabled(this)) {
            TripRecordingService.monitorAutomaticTrips(this)
        }
    }

    /**
     * Binding is what makes the recorder sample for this screen; it keeps sampling after the
     * unbind only when a trip is actually being recorded.
     */
    override fun onStart() {
        super.onStart()
        loadRecentTrips()
        bindService(
            Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        recorder?.clearListener(this)
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
            service.stopTrip(::loadRecentTrips)
            service.latest?.let(::render)
        } else {
            TripRecordingService.start(this)
        }
    }

    private fun render(value: EnergySnapshot) {
        val powerValidated = DashboardReadings.isPowerValidated(value.firmware)
        val calculationSnapshot = if (powerValidated || value.batteryPowerKw == null) value
            else value.copy(batteryPowerKw = null)
        val powerMissingReason = DashboardReadings.powerUnavailableReason(value.firmware)
        val consumptionReading = consumption.add(calculationSnapshot, powerMissingReason)
        val currentTrip = EnergyTripSession.current(value.timestampMs)
        val powerEvidence = CarPropertyEvidence.batteryPowerEvidence(value.firmware)
        val adaptiveRange = rangeEstimator.estimate(
            calculationSnapshot,
            currentTrip,
            trustedRecentTrips(powerEvidence),
            powerMissingReason,
        )
        val readings = DashboardReadings.of(
            value,
            currentTrip,
            consumptionReading.smoothedInstantaneous,
            adaptiveRange,
        )
        latestReadings = readings
        renderReadings(readings, value.firmware)

        val hasVehicleData = value.hasVehicleData
        binding.dataStatus.text = getString(
            if (hasVehicleData) R.string.status_vehicle_connected else R.string.status_waiting_for_vehicle
        )
        binding.dataStatus.setTextColor(getColor(if (hasVehicleData) R.color.ev_ok else R.color.ev_warn))

        // Recording controls are for a parked driver. Live values remain passive and readable
        // while moving; the app never presents an overlay or asks for attention.
        val parked = value.speedKmh?.let { it <= 0.1f } == true
        binding.tripAction.isEnabled = parked
        binding.automaticDetection.isEnabled = parked
        val automatic = recorder?.automaticDetectionEnabled
            ?: TripRecordingService.isAutomaticDetectionEnabled(this)
        setAutomaticSwitchChecked(automatic)
        binding.tripAction.text = getString(
            if (EnergyTripSession.isRecording) R.string.action_stop_trip else R.string.action_start_trip
        )
        binding.tripHint.text = tripHint(value, parked, automatic)
    }

    private fun tripHint(value: EnergySnapshot, parked: Boolean, automatic: Boolean): String {
        val recording = EnergyTripSession.isRecording
        if (automatic && value.speedKmh == null) {
            return getString(
                if (recording) R.string.trip_automatic_recording_speed_unavailable
                else R.string.trip_automatic_speed_unavailable
            )
        }
        if (automatic) {
            return getString(
                when (recorder?.detectorState ?: TripDetector.State.IDLE) {
                    TripDetector.State.IDLE -> R.string.trip_automatic_waiting
                    TripDetector.State.ARMED -> R.string.trip_automatic_confirming_motion
                    TripDetector.State.RECORDING -> R.string.trip_recording
                    TripDetector.State.ENDING -> R.string.trip_automatic_confirming_end
                }
            )
        }
        return getString(
            when {
                value.speedKmh == null -> R.string.trip_control_speed_unavailable
                !parked -> R.string.trip_control_park_to_change
                recording -> R.string.trip_recording
                else -> R.string.trip_ready
            }
        )
    }

    private fun changeAutomaticDetection(enabled: Boolean) {
        val service = recorder
        val parked = service?.latest?.speedKmh?.let { it <= 0.1f } == true
        if (!parked) {
            setAutomaticSwitchChecked(
                service?.automaticDetectionEnabled
                    ?: TripRecordingService.isAutomaticDetectionEnabled(this)
            )
            return
        }
        TripRecordingService.storeAutomaticDetectionEnabled(this, enabled)
        service.setAutomaticDetectionEnabled(enabled)
        if (enabled) TripRecordingService.monitorAutomaticTrips(this)
        service.latest?.let(::render)
    }

    private fun setAutomaticSwitchChecked(checked: Boolean) {
        updatingAutomaticSwitch = true
        binding.automaticDetection.isChecked = checked
        updatingAutomaticSwitch = false
    }

    private fun renderReadings(
        readings: DashboardReadings,
        firmware: FirmwareInfo.Gen? = null,
    ) {
        bind(binding.socValue, R.string.label_soc, readings.soc, PATTERN_SOC, SOC_UNAVAILABLE)
        bind(binding.rangeValue, R.string.label_range, readings.range, PATTERN_DISTANCE)
        bind(
            binding.adaptiveRangeValue,
            R.string.label_adaptive_range,
            readings.adaptiveRange,
            PATTERN_DISTANCE,
            DISTANCE_UNAVAILABLE,
        )
        bind(binding.speedValue, R.string.label_speed, readings.speed, PATTERN_SPEED)
        bind(binding.powerValue, R.string.label_power, readings.power, PATTERN_POWER, POWER_UNAVAILABLE)
        renderPowerFlow(readings.power, firmware)
        renderClimate(readings.climate, firmware)
        bind(
            binding.instantConsumptionValue,
            R.string.label_instant_consumption,
            readings.instantConsumption,
            PATTERN_CONSUMPTION,
        )
        bind(binding.tripDistanceValue, R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE)
        bind(binding.tripEnergyValue, R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY)
        bind(binding.tripRegenValue, R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY)
        bind(
            binding.tripConsumptionValue, R.string.label_trip_average_consumption,
            readings.tripConsumption, PATTERN_CONSUMPTION,
        )
        val recorded = provenance.renderWith(readings.tripDuration, transform = ::duration)
        binding.tripDurationValue.text = recorded
        binding.tripDurationValue.contentDescription =
            provenance.describe(getString(R.string.label_duration), readings.tripDuration, recorded)
    }

    private fun renderClimate(readings: ClimateReadings, firmware: FirmwareInfo.Gen?) {
        bind(
            binding.outsideTempValue,
            R.string.label_outside_temp,
            readings.outsideTemp,
            PATTERN_TEMP,
        )
        bind(
            binding.cabinTempValue,
            R.string.label_cabin_temp,
            readings.cabinTemp,
            PATTERN_TEMP,
        )
        bind(
            binding.batteryTempValue,
            R.string.label_battery_temp,
            readings.batteryTemp,
            PATTERN_TEMP,
        )
        bindState(binding.climatePowerValue, R.string.label_climate_power, readings.hvacOn)
        bindState(binding.climateAcValue, R.string.label_climate_ac, readings.acOn)
        bindState(binding.climateAutoValue, R.string.label_climate_auto, readings.autoOn)
        bindWith(binding.climateFanValue, R.string.label_climate_fan, readings.fan) {
            getString(R.string.climate_fan_value, it.level, it.maximum)
        }
        bind(
            binding.climateDriverTargetValue,
            R.string.label_climate_driver_target,
            readings.driverTarget,
            PATTERN_TEMP,
        )
        bind(
            binding.climatePassengerTargetValue,
            R.string.label_climate_passenger_target,
            readings.passengerTarget,
            PATTERN_TEMP,
        )
        bindState(binding.climateEconValue, R.string.label_climate_econ, readings.econOn)
        bindState(
            binding.climateRecirculationValue,
            R.string.label_climate_recirculation,
            readings.recirculationOn,
        )
        binding.climateAvailability.text = climateAvailability(readings, firmware)
    }

    private fun bindState(view: TextView, label: Int, value: Provenanced<Boolean>) =
        bindWith(view, label, value) {
            getString(if (it) R.string.state_on else R.string.state_off)
        }

    private fun <T : Any> bindWith(
        view: TextView,
        label: Int,
        value: Provenanced<T>,
        transform: (T) -> String,
    ) {
        val rendered = provenance.renderWith(value, transform = transform)
        view.text = rendered
        view.contentDescription = provenance.describe(getString(label), value, rendered)
    }

    private fun climateAvailability(
        readings: ClimateReadings,
        firmware: FirmwareInfo.Gen?,
    ): String = buildList {
        add(getString(R.string.climate_state_only))
        if (firmware == null) {
            add(getString(R.string.climate_missing_waiting))
        } else {
            readings.unavailableReasons.forEach { reason ->
                add(when (reason) {
                    UnavailableReason.UNSUPPORTED_FIRMWARE ->
                        getString(R.string.climate_missing_unsupported)
                    UnavailableReason.UNVALIDATED_FIRMWARE ->
                        getString(R.string.climate_missing_unvalidated, firmware.name)
                    else -> getString(R.string.climate_missing_signal)
                })
            }
        }
    }.distinct().joinToString(" · ")

    private fun renderPowerFlow(power: Provenanced<Float>, firmware: FirmwareInfo.Gen?) {
        binding.powerFlow.showPower(power.value)
        val state = when {
            firmware == null -> R.string.power_state_waiting
            power.reason == UnavailableReason.UNVALIDATED_FIRMWARE ->
                R.string.power_state_unvalidated
            power.reason == UnavailableReason.UNSUPPORTED_FIRMWARE ->
                R.string.power_state_unsupported
            power.value == null -> R.string.power_state_unavailable
            powerFlowDirection(power.value) == PowerFlowDirection.OUTPUT ->
                R.string.power_state_output
            powerFlowDirection(power.value) == PowerFlowDirection.REGENERATION ->
                R.string.power_state_regeneration
            else -> R.string.power_state_idle
        }
        binding.powerState.text = if (firmware == null) getString(state)
        else getString(state, firmware.name)
        binding.powerState.contentDescription = provenance.describe(
            getString(R.string.label_power),
            power,
            binding.powerState.text.toString(),
        )
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
        consumption.reset()
        renderReadings(latestReadings)
        binding.dataStatus.text = getString(R.string.status_waiting_for_vehicle)
        binding.tripAction.isEnabled = false
        binding.automaticDetection.isEnabled = false
        val automatic = TripRecordingService.isAutomaticDetectionEnabled(this)
        setAutomaticSwitchChecked(automatic)
        binding.historyAction.isEnabled = true
        binding.tripHint.text = getString(
            if (automatic) R.string.trip_automatic_speed_unavailable
            else R.string.trip_control_speed_unavailable
        )
    }

    private fun trustedRecentTrips(
        evidence: BatteryPowerEvidence?,
    ): List<EnergyTripSummary> {
        val source = recentTrips
        if (source !== trustedTripsSource || evidence != trustedTripsEvidence) {
            trustedTripsSource = source
            trustedTripsEvidence = evidence
            trustedTripsCache = DashboardReadings.trustedPowerTrips(source, evidence)
        }
        return trustedTripsCache
    }

    /** History is bounded but still disk-backed; never parse it on the one-second UI path. */
    private fun loadRecentTrips() {
        runCatching {
            background.execute {
                val summaries = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).readSummaries()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    recentTrips = summaries
                    recorder?.latest?.let(::render)
                }
            }
        }
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
                    .setNeutralButton(R.string.diagnostics_export_usb) { _, _ ->
                        exportDiagnostics(body)
                    }
                    .setPositiveButton(R.string.action_close, null)
                    .show()
            }
        }
    }

    /** Export remains a parked, explicit action; missing speed fails closed with a reason. */
    private fun exportDiagnostics(report: String) {
        if (showDiagnosticExportRefusal(diagnosticExportDecision(recorder?.latest))) return
        val appContext = applicationContext
        background.execute {
            val roots = DiagnosticUsbStorage.roots(appContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    toastLong(R.string.diagnostics_export_no_usb)
                } else {
                    chooseDiagnosticDestination(report, roots)
                }
            }
        }
    }

    private fun chooseDiagnosticDestination(report: String, roots: List<File>) {
        val labels = roots.map(File::getAbsolutePath).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_export_pick_usb)
            .setItems(labels) { _, which -> writeDiagnostic(report, roots[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun writeDiagnostic(report: String, directory: File) {
        val appContext = applicationContext
        val service = recorder
        background.execute {
            // Root discovery and the user's choice can take arbitrarily long. Re-read the
            // volatile latest sample at the last responsible moment so parking cannot go stale.
            val decision = diagnosticExportDecision(service?.latest)
            val file = if (decision == DiagnosticExportPolicy.Decision.ALLOWED) {
                DiagnosticExporter.export(appContext, report, directory)
            } else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (showDiagnosticExportRefusal(decision)) {
                    Unit
                } else if (file == null) {
                    toastLong(R.string.diagnostics_export_failed)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.diagnostics_export_ok, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun diagnosticExportDecision(sample: EnergySnapshot?): DiagnosticExportPolicy.Decision =
        DiagnosticExportPolicy.decide(
            speedKmh = sample?.speedKmh,
            sampledAtMs = sample?.timestampMs,
            nowMs = System.currentTimeMillis(),
        )

    /** @return true when a visible refusal was shown. */
    private fun showDiagnosticExportRefusal(decision: DiagnosticExportPolicy.Decision): Boolean =
        when (decision) {
            DiagnosticExportPolicy.Decision.SPEED_UNAVAILABLE -> {
                toastLong(R.string.diagnostics_export_speed_unavailable)
                true
            }
            DiagnosticExportPolicy.Decision.VEHICLE_MOVING -> {
                toastLong(R.string.diagnostics_export_park_vehicle)
                true
            }
            DiagnosticExportPolicy.Decision.ALLOWED -> false
        }

    private fun toastLong(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun diagnosticsReport(): String {
        val crash = CrashLogger.read(applicationContext)
        val recentLog = AppLogger.entries.takeLast(30).joinToString("\n") {
            "[${it.time}] ${it.level}/${it.tag}: ${it.msg}"
        }
        return DiagnosticExporter.bounded(buildString {
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
        })
    }

    private fun describeReadings(readings: DashboardReadings): List<String> {
        val numeric = listOf(
            Triple(R.string.label_soc, readings.soc, PATTERN_SOC),
            Triple(R.string.label_range, readings.range, PATTERN_DISTANCE),
            Triple(R.string.label_adaptive_range, readings.adaptiveRange, PATTERN_DISTANCE),
            Triple(R.string.label_speed, readings.speed, PATTERN_SPEED),
            Triple(R.string.label_power, readings.power, PATTERN_POWER),
            Triple(R.string.label_outside_temp, readings.climate.outsideTemp, PATTERN_TEMP),
            Triple(R.string.label_cabin_temp, readings.climate.cabinTemp, PATTERN_TEMP),
            Triple(R.string.label_battery_temp, readings.climate.batteryTemp, PATTERN_TEMP),
            Triple(
                R.string.label_climate_driver_target,
                readings.climate.driverTarget,
                PATTERN_TEMP,
            ),
            Triple(
                R.string.label_climate_passenger_target,
                readings.climate.passengerTarget,
                PATTERN_TEMP,
            ),
            Triple(
                R.string.label_instant_consumption,
                readings.instantConsumption,
                PATTERN_CONSUMPTION,
            ),
            Triple(R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE),
            Triple(R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY),
            Triple(R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY),
            Triple(
                R.string.label_trip_average_consumption,
                readings.tripConsumption,
                PATTERN_CONSUMPTION,
            ),
        ).map { (label, value, pattern) ->
            provenance.describe(getString(label), value, provenance.render(value, pattern))
        }
        val states = listOf(
            R.string.label_climate_power to readings.climate.hvacOn,
            R.string.label_climate_ac to readings.climate.acOn,
            R.string.label_climate_auto to readings.climate.autoOn,
            R.string.label_climate_econ to readings.climate.econOn,
            R.string.label_climate_recirculation to readings.climate.recirculationOn,
        ).map { (label, value) ->
            val rendered = provenance.renderWith(value) {
                getString(if (it) R.string.state_on else R.string.state_off)
            }
            provenance.describe(getString(label), value, rendered)
        }
        val fan = provenance.renderWith(readings.climate.fan) {
            getString(R.string.climate_fan_value, it.level, it.maximum)
        }
        return numeric + states + provenance.describe(
            getString(R.string.label_climate_fan),
            readings.climate.fan,
            fan,
        )
    }

    private fun duration(milliseconds: Long): String {
        val totalMinutes = milliseconds / 60_000L
        return format("%d:%02d", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.getDefault(), pattern, *args)

    private companion object {
        const val TAG = "EVChargePilot"
        const val HISTORY_FILE = "trips.json"
        const val DASH = "—"
        // The unit-bearing fields keep their unit while unavailable so the layout does not
        // shift the moment the vehicle starts answering.
        const val SOC_UNAVAILABLE = "— %"
        const val POWER_UNAVAILABLE = "— kW"
        const val DISTANCE_UNAVAILABLE = "— km"
        const val PATTERN_SOC = "%.1f %%"
        const val PATTERN_SPEED = "%.0f km/h"
        const val PATTERN_POWER = "%+.1f kW"
        const val PATTERN_TEMP = "%.0f °C"
        const val PATTERN_DISTANCE = "%.1f km"
        const val PATTERN_ENERGY = "%.2f kWh"
        const val PATTERN_CONSUMPTION = "%.1f kWh/100 km"
    }
}
