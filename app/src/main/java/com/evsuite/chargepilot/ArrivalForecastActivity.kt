package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.evsuite.chargepilot.databinding.ActivityArrivalForecastBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.ArrivalSocForecast
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.SocRateEstimator
import com.evsuite.hardware.telemetry.TripDetector
import com.evsuite.hardware.telemetry.UnavailableReason
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What the charge will read on arrival, if the head unit knows where the driver is going.
 *
 * The obvious way to build this — the energy model, a distance, a pack capacity — cannot work
 * on this vehicle: CP-003 proved battery power is declared and never published, so nothing can
 * integrate kWh. State of charge per kilometre needs no energy unit and both its inputs read,
 * which is what this screen shows.
 *
 * **It reads the navigation, it never registers with it.** `SaicNavGuidance.readNow` answers
 * from synchronous getters; the listener registration that CP-040 used to discover them stays
 * in the unstable channel, so a stable build never joins the adapter's callback fan-out.
 *
 * Everything happens on [worker]: the vehicle binder calls, the trip file, and the arithmetic.
 * The dashboard's own sampling must not wait on any of it.
 *
 * **CP-083: the Details page.** The companion page keeps only what helps at speed; the car and
 * cabin grid, the current trip and its parked-only controls moved here from it, rendered from
 * the recorder's samples on the main thread like they were there.
 */
class ArrivalForecastActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.ARRIVAL

    private lateinit var binding: ActivityArrivalForecastBinding
    private lateinit var provenance: ProvenanceText

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chargepilot-arrival")
    }

    @Volatile
    private var snapshot: EnergySnapshot? = null

    /** Read once: the trip file does not change while this screen is open. */
    @Volatile
    private var rate: SocRate? = null

    @Volatile
    private var ratePending = true

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var refresh: ScheduledFuture<*>? = null

    /** The declared pack, so a trip with no power interval can still price its SoC drop. */
    private var pack: BatteryCapacityConfig? = null
    private var updatingAutomaticSwitch = false

    /**
     * CP-062. The destination the driver just chose, on its way to the plan. A cancel is
     * silence — they went to look and came back.
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
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            snapshot = value.latest
            setAutomaticSwitchChecked(value.automaticDetectionEnabled)
            value.setListener(this@ArrivalForecastActivity) { latest ->
                snapshot = latest
                renderLive(latest)
            }
            value.latest?.let(::renderLive)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            snapshot = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArrivalForecastBinding.inflate(layoutInflater)
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
        binding.tripAction.isEnabled = false
        binding.automaticDetection.isEnabled = false
        worker.execute {
            SaicNavGuidance.connect(applicationContext)
            loadRate()
        }
    }

    override fun onStart() {
        super.onStart()
        pack = VehicleSettings.read(this).pack
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        refresh = worker.scheduleWithFixedDelay(::recompute, 0L, REFRESH_SECONDS, TimeUnit.SECONDS)
    }

    override fun onStop() {
        refresh?.cancel(false)
        refresh = null
        recorder?.clearListener(this)
        recorder = null
        if (bound) unbindService(connection)
        bound = false
        snapshot = null
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * The rate this driver has actually spent, when enough trips can be vouched for.
     *
     * Trips recorded before the speed conversion was fixed are 3.6 times too long and are
     * refused by the estimator, so a fresh install has no history and falls back to the
     * vehicle's own range — which is the point of having two sources.
     */
    private fun loadRate() {
        val generation = FirmwareInfo.getGeneration()
        val trips = runCatching {
            EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).readSummaries()
        }.getOrDefault(emptyList())
        rate = SocRateEstimator.fromTrips(trips, generation)
        ratePending = false
    }

    private fun recompute() {
        val latest = snapshot
        val generation = FirmwareInfo.getGeneration()
        val guidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
        val remainingKm = guidance?.remainingDistanceKm(generation)
        val socPercent = latest?.socPercent?.toDouble()
        val effective = rate ?: SocRateEstimator.fromVehicleRange(
            socPercent,
            latest?.rangeKm?.toDouble(),
        )
        val arrival = ArrivalSocForecast.of(socPercent, remainingKm, effective)
        val reach = ArrivalSocForecast.rangeAtRateKm(socPercent, effective)
        val state = ViewState(
            arrival = arrival,
            reachKm = reach,
            remainingKm = remainingKm,
            minutes = guidance?.remainingMinutes,
            road = guidance?.road,
            rate = effective,
            routeKnown = remainingKm != null,
            ratePending = ratePending,
        )
        runOnUiThread { if (!isFinishing && !isDestroyed) render(state) }
    }

    private fun render(state: ViewState) {
        val arrival = provenance.render(state.arrival, PATTERN_SOC_WHOLE)
        binding.arrivalValue.text = arrival
        binding.arrivalValue.contentDescription =
            provenance.describe(getString(R.string.arrival_label), state.arrival, arrival)

        binding.arrivalStatus.text = when {
            !state.routeKnown -> getString(R.string.arrival_no_route)
            state.ratePending -> getString(R.string.arrival_rate_loading)
            !state.arrival.isAvailable -> getString(R.string.arrival_refused)
            // The head unit publishes a remaining time separately from a remaining distance and
            // sometimes only the distance. An em dash says so; a `0` would read as "you are
            // there", which is the one thing this app promises never to print.
            else -> getString(
                R.string.arrival_route,
                String.format(Locale.getDefault(), "%.1f", state.remainingKm ?: 0.0),
                state.minutes?.toString() ?: getString(R.string.value_unavailable),
                state.road.orEmpty(),
            )
        }

        binding.arrivalDetail.visibility = if (state.rate == null) View.GONE else View.VISIBLE
        state.rate?.let { rate ->
            binding.arrivalDetail.text = getString(
                R.string.arrival_detail,
                getString(
                    when (rate.source) {
                        SocRate.Source.TRIP_HISTORY -> R.string.arrival_source_trips
                        SocRate.Source.VEHICLE_RANGE -> R.string.arrival_source_range
                    }
                ),
                rate.sampleCount,
                String.format(Locale.getDefault(), "%.3f", rate.percentPerKm),
                provenance.render(state.reachKm, PATTERN_DISTANCE_WHOLE),
            )
        }
    }

    /** The car, the cabin and the current trip, redrawn on every recorder sample. */
    private fun renderLive(value: EnergySnapshot) {
        fun <T : Any> notShown() = Provenanced.unavailable<T>(UnavailableReason.SIGNAL_ABSENT)
        val readings = DashboardReadings.of(
            value,
            EnergyTripSession.current(value.timestampMs),
            notShown(),
            notShown(),
            pack,
        )
        bind(binding.rangeValue, R.string.label_range, readings.range, PATTERN_DISTANCE)
        renderClimate(readings.climate, value.firmware)
        bind(binding.tripDistanceValue, R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE)
        bind(binding.tripEnergyValue, R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY)
        bind(binding.tripRegenValue, R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY)
        val recorded = provenance.renderWith(readings.tripDuration, transform = ::duration)
        binding.tripDurationValue.text = recorded
        binding.tripDurationValue.contentDescription =
            provenance.describe(getString(R.string.label_duration), readings.tripDuration, recorded)

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

    private fun toggleTrip() {
        val service = recorder
        if (EnergyTripSession.isRecording) {
            if (service == null) {
                AppLogger.w(TAG, "trip stop ignored: recorder not bound")
                return
            }
            // The trip just stored is a trip the arrival rate can now vouch for.
            service.stopTrip { runCatching { worker.execute(::loadRate) } }
            service.latest?.let(::renderLive)
        } else {
            TripRecordingService.start(this)
        }
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
        service.latest?.let(::renderLive)
    }

    private fun setAutomaticSwitchChecked(checked: Boolean) {
        updatingAutomaticSwitch = true
        binding.automaticDetection.isChecked = checked
        updatingAutomaticSwitch = false
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

    private fun duration(milliseconds: Long): String {
        val totalMinutes = milliseconds / 60_000L
        return format("%d:%02d", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun format(pattern: String, vararg args: Any): String =
        String.format(Locale.getDefault(), pattern, *args)

    private data class ViewState(
        val arrival: Provenanced<Double>,
        val reachKm: Provenanced<Double>,
        val remainingKm: Double?,
        val minutes: Int?,
        val road: String?,
        val rate: SocRate?,
        val routeKnown: Boolean,
        val ratePending: Boolean,
    )

    private companion object {
        const val TAG = "ArrivalForecast"
        const val HISTORY_FILE = "trips.json"
        const val DASH = "—"

        /** Slow on purpose: a route shortens by metres a second and this is not a speedometer. */
        const val REFRESH_SECONDS = 5L
    }
}
