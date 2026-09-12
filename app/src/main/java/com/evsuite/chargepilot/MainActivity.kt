package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.evsuite.chargepilot.databinding.ActivityMainBinding
import com.evsuite.chargepilot.update.UpdateHook
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.AdaptiveRangeEstimator
import com.evsuite.hardware.telemetry.ConsumptionCalculator
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.TripDetector
import com.evsuite.hardware.telemetry.UnavailableReason
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.ENERGY

    private lateinit var binding: ActivityMainBinding
    private lateinit var provenance: ProvenanceText
    private val consumption = ConsumptionCalculator()
    private val rangeEstimator = AdaptiveRangeEstimator()

    /**
     * CP-058. Costs no request; the line it produces is the whole of it on this screen.
     *
     * Lazy because an activity has no application context until it is attached, and a field
     * initialiser runs before that.
     */
    private val drift by lazy { DriftCompanion(this) }
    /** Bounded history parsing and the drift fit never run on the main thread. */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-background")
    }

    /** The recorder owns the sampler; this screen is one of its readers. */
    private var recorder: TripRecordingService? = null
    /** Exact normalized service frame; the parked-only actions re-read it rather than the UI. */
    @Volatile private var latestSnapshot: EnergySnapshot? = null
    /** Immutable snapshot loaded off the UI thread; the store writes newest trips first. */
    @Volatile private var recentTrips: List<EnergyTripSummary> = emptyList()
    /** Rebuilt only when history or firmware evidence changes, never on every 1 Hz frame. */
    private var trustedTripsSource: List<EnergyTripSummary> = emptyList()
    private var trustedTripsEvidence: BatteryPowerEvidence? = null
    private var trustedTripsCache: List<EnergyTripSummary> = emptyList()
    private var updatingAutomaticSwitch = false

    private val vehiclePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { outcome ->
        // A denial is not an error state to recover from: every reading it covers stays null,
        // which the dashboard already renders and explains. It is logged so a diagnostic
        // report distinguishes it from a firmware that publishes nothing.
        outcome.filterValues { !it }.keys.forEach {
            AppLogger.w(TAG, "vehicle permission denied: $it")
        }
    }

    /**
     * CP-062. The destination the driver just chose, on its way to the plan.
     *
     * Choosing where you are going was a page of its own in the top bar, which put the most
     * frequent question in this app two taps and a page change away from the screen the driver
     * lands on. It is now the dashboard's own action: the chooser opens, and the plan for what
     * they picked opens after it. A cancel is silence — they went to look and came back.
     */
    private val destination = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val place = DestinationActivity.place(result.data) ?: return@registerForActivityResult
        startActivity(
            DestinationActivity.carry(Intent(this, ChargeStopActivity::class.java), place)
        )
    }

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
        binding.chooseDestinationAction.setOnClickListener {
            destination.launch(DestinationActivity.intent(this))
        }
        binding.tripAction.setOnClickListener { toggleTrip() }
        setAutomaticSwitchChecked(TripRecordingService.isAutomaticDetectionEnabled(this))
        binding.automaticDetection.setOnCheckedChangeListener { _, enabled ->
            if (!updatingAutomaticSwitch) changeAutomaticDetection(enabled)
        }
        binding.driftLine.setOnClickListener { forgetPlan() }
        binding.aboutAction.text = getString(R.string.about_version_badge, appVersion())
        binding.aboutAction.setOnClickListener { showAbout() }
        renderUnavailable()
        requestVehiclePermissions()
        if (TripRecordingService.isAutomaticDetectionEnabled(this)) {
            TripRecordingService.monitorAutomaticTrips(this)
        }
        // Unstable only, and nothing here waits on it: the check runs on its own thread and
        // speaks only when a newer build is already downloaded. Stable contains no updater.
        UpdateHook.checkInBackground(this)
    }

    /**
     * Asked from the only screen the driver launches, because the recorder is a service and a
     * service cannot ask. Nothing waits on the answer: the dashboard renders unavailable
     * readings meanwhile, and the next sample after a grant carries the real values.
     */
    private fun requestVehiclePermissions() {
        val missing = VehiclePermissions.missing {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) vehiclePermissions.launch(missing)
    }

    /**
     * Binding is what makes the recorder sample for this screen; it keeps sampling after the
     * unbind only when a trip is actually being recorded.
     */
    override fun onStart() {
        super.onStart()
        loadRecentTrips()
        // The consumption fit reads the whole trip history, so it happens once per visit to
        // this screen and only when there is actually a plan being followed.
        background.execute { runCatching { drift.load() } }
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
        latestSnapshot = value
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
        DashboardFrame.publish(readings)
        renderReadings(readings, value.firmware)

        val hasVehicleData = value.hasVehicleData
        renderDataStatus(hasVehicleData)

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
        renderDrift(value, parked)
    }

    /**
     * One line, and only the line.
     *
     * The companion decides whether there is anything to say and gates its own recomputation to
     * once a minute; this renders whatever comes back. Nothing here interrupts: no dialog, no
     * sound, no colour carrying the meaning on its own. A driver at 130 km/h gets a glance.
     *
     * Forgetting the plan is offered only when the car is stopped, and the offer is a line of
     * text rather than a button, because a button on this line would be a target to miss at
     * speed. The tap itself re-checks the gate.
     */
    private fun renderDrift(value: EnergySnapshot, parked: Boolean) {
        val line = drift.line(value, value.timestampMs)
        binding.driftLine.visibility = if (line == null) View.GONE else View.VISIBLE
        if (line == null) return
        binding.driftLine.text =
            if (parked) line + getString(R.string.drift_forget_hint) else line
        binding.driftLine.isClickable = parked
    }

    /** Parked only, and re-checked here rather than trusted from the last frame. */
    private fun forgetPlan() {
        val speed = latestSnapshot?.speedKmh
        if (speed == null || speed > 0.1f) return
        drift.forget()
        binding.driftLine.visibility = View.GONE
        Toast.makeText(this, R.string.drift_forgotten, Toast.LENGTH_SHORT).show()
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

    /**
     * The screen carries no status banner: when the car answers, this line says where the
     * number above it comes from, and when it does not, the same line says so in red. Text
     * and colour together, because a driver reads the word faster than the hue and roughly
     * one in twelve cannot separate the hues at all.
     */
    private fun renderDataStatus(hasVehicleData: Boolean) {
        binding.dataStatus.setText(
            if (hasVehicleData) R.string.soc_vehicle_reported else R.string.status_waiting_for_vehicle
        )
        binding.dataStatus.setTextColor(
            getColor(if (hasVehicleData) R.color.ev_text_secondary else R.color.ev_error)
        )
    }

    /** What the driver can read back down a phone line, build suffix and all. */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: getString(R.string.about_version_unknown)

    /**
     * Version, authorship and the repository as a QR. The address is not a link: this head
     * unit has no browser to hand it to, and a tap that does nothing is worse than a code
     * the phone in the driver's pocket can read off the glass.
     */
    private fun showAbout() {
        val content = layoutInflater.inflate(R.layout.dialog_about, null)
        content.findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version, appVersion())
        QrCode.generate(REPOSITORY_URL, QR_SIZE_PX)?.let {
            content.findViewById<ImageView>(R.id.aboutQrCode).setImageBitmap(it)
        }
        val dialog = MaterialAlertDialogBuilder(this).setView(content).create()
        content.findViewById<MaterialButton>(R.id.aboutClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
        // The card draws its own surface, outline and radius; the dialog window must not
        // draw a second one behind it.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun renderUnavailable() {
        DashboardFrame.publish(DashboardReadings.empty())
        consumption.reset()
        renderReadings(DashboardFrame.readings)
        renderDataStatus(false)
        binding.tripAction.isEnabled = false
        binding.automaticDetection.isEnabled = false
        val automatic = TripRecordingService.isAutomaticDetectionEnabled(this)
        setAutomaticSwitchChecked(automatic)
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
        const val REPOSITORY_URL = "https://github.com/malys/EVChargePilot"
        /** Encoded once at this size, then scaled down into a 208dp view. */
        const val QR_SIZE_PX = 416
        // The unit-bearing fields keep their unit while unavailable so the layout does not
        // shift the moment the vehicle starts answering.
        const val SOC_UNAVAILABLE = "— %"
        const val POWER_UNAVAILABLE = "— kW"
        const val DISTANCE_UNAVAILABLE = "— km"
    }
}
