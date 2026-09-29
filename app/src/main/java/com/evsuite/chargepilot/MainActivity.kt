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
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.AdaptiveRangeEstimator
import com.evsuite.hardware.telemetry.ArrivalSocForecast
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.ConsumptionCalculator
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.SocRateEstimator
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
    private var loadedHistoryRevision = -1
    /** Exact normalized service frame; the parked-only actions re-read it rather than the UI. */
    @Volatile private var latestSnapshot: EnergySnapshot? = null

    /** The declared pack, so a trip with no power interval can still price its SoC drop. */
    @Volatile private var pack: BatteryCapacityConfig? = null
    /** Immutable snapshot loaded off the UI thread; the store writes newest trips first. */
    @Volatile private var recentTrips: List<EnergyTripSummary> = emptyList()
    /** Rebuilt only when history or firmware evidence changes, never on every 1 Hz frame. */
    private var trustedTripsSource: List<EnergyTripSummary> = emptyList()
    private var trustedTripsEvidence: BatteryPowerEvidence? = null
    private var trustedTripsCache: List<EnergyTripSummary> = emptyList()

    /** CP-083. The charge on arrival while a route is guided; null when there is no route. */
    @Volatile private var arrival: Provenanced<Double>? = null
    @Volatile private var arrivalRoute: String? = null
    private var arrivalReadAtMs = 0L

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = bound
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
        binding.driftLine.setOnClickListener { forgetPlan() }
        binding.aboutAction.text = getString(R.string.about_version_badge, appVersion())
        binding.aboutAction.setOnClickListener { showAbout() }
        val versionGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                binding.aboutAction.performClick()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                UpdateHook.checkInBackground(this@MainActivity)
                return true
            }
        })
        binding.aboutAction.setOnTouchListener { _, event ->
            versionGestures.onTouchEvent(event)
            true
        }
        renderUnavailable()
        requestVehiclePermissions()
        if (TripRecordingService.isAutomaticDetectionEnabled(this)) {
            TripRecordingService.monitorAutomaticTrips(this)
        }
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
        pack = VehicleSettings.read(this).pack
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

    private fun render(value: EnergySnapshot) {
        latestSnapshot = value
        recorder?.historyRevision?.let { revision ->
            if (loadedHistoryRevision < 0) {
                loadedHistoryRevision = revision
            } else if (revision != loadedHistoryRevision) {
                loadedHistoryRevision = revision
                loadRecentTrips()
            }
        }
        // The calculators are fed the reading as it arrived, unvalidated firmware included.
        // A power figure the vehicle publishes is still a power figure; what an unproven
        // property costs it is its provenance, not its existence, and DashboardReadings
        // labels it derived rather than measured. Blanking it here emptied instantaneous
        // consumption, adaptive range and the trip totals on the only firmware this car has.
        val powerMissingReason = DashboardReadings.powerUnavailableReason(value.firmware)
        val consumptionReading = consumption.add(value, powerMissingReason)
        val currentTrip = EnergyTripSession.current(value.timestampMs)
        val powerEvidence = CarPropertyEvidence.powerModelEvidence(value.firmware)
        val adaptiveRange = rangeEstimator.estimate(
            value,
            currentTrip,
            trustedRecentTrips(powerEvidence),
            powerMissingReason,
        )
        val readings = DashboardReadings.of(
            value,
            currentTrip,
            consumptionReading.smoothedInstantaneous,
            adaptiveRange,
            pack,
        )
        DashboardFrame.publish(readings)
        renderReadings(readings, value.firmware)

        val hasVehicleData = value.hasVehicleData
        renderDataStatus(hasVehicleData)

        val parked = value.speedKmh?.let { it <= 0.1f } == true
        refreshArrival(value)
        renderReach(readings)
        renderDrift(value, parked)
        renderEco()
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

    /**
     * The verdict, and — only if it was asked for — the advice.
     *
     * The band is always drawn, because it is a statement about the drive and not an
     * instruction; the line and the voice are two separate opt-ins on top of it. The recorder
     * feeds the coach and speaks (CP-083), so the voice carries on behind the map; this only
     * draws the verdict it last published.
     */
    private fun renderEco() {
        val eco = recorder?.eco ?: return
        val verdict = DashboardFrame.eco
        binding.ecoValue.text = eco.band(verdict)
        binding.ecoDelta.text = eco.delta(verdict)
        val line = eco.line(verdict)
        binding.ecoAdviceLine.visibility = if (line == null) View.GONE else View.VISIBLE
        binding.ecoAdviceLine.text = line.orEmpty()
    }

    /** Parked only, and re-checked here rather than trusted from the last frame. */
    private fun forgetPlan() {
        val speed = latestSnapshot?.speedKmh
        if (speed == null || speed > 0.1f) return
        drift.forget()
        binding.driftLine.visibility = View.GONE
        Toast.makeText(this, R.string.drift_forgotten, Toast.LENGTH_SHORT).show()
    }

    private fun renderReadings(
        readings: DashboardReadings,
        firmware: FirmwareInfo.Gen? = null,
    ) {
        bind(binding.socValue, R.string.label_soc, readings.soc, PATTERN_SOC, SOC_UNAVAILABLE)
        bind(binding.powerValue, R.string.label_power, readings.power, PATTERN_POWER, POWER_UNAVAILABLE)
        renderPowerFlow(readings.power, firmware)
        bind(
            binding.instantConsumptionValue,
            R.string.label_instant_consumption,
            readings.instantConsumption,
            PATTERN_CONSUMPTION,
        )
        bind(
            binding.tripConsumptionValue, R.string.label_trip_average_consumption,
            readings.tripConsumption, PATTERN_CONSUMPTION,
        )
    }

    /**
     * CP-083. The middle hero answers "will I make it": the charge on arrival while the car's
     * navigation guides a route, the adaptive range otherwise. Same figure and same refusal as
     * the Details page; a refused forecast shows its dash rather than falling back to a range,
     * because a range under the arrival label would read as an arrival.
     */
    private fun renderReach(readings: DashboardReadings) {
        val forecast = arrival
        if (forecast == null) {
            binding.reachLabel.setText(R.string.label_adaptive_range)
            bind(
                binding.reachValue,
                R.string.label_adaptive_range,
                readings.adaptiveRange,
                PATTERN_DISTANCE_WHOLE,
                DISTANCE_UNAVAILABLE,
            )
            binding.reachDetail.text = ""
            return
        }
        binding.reachLabel.setText(R.string.label_arrival)
        bind(binding.reachValue, R.string.arrival_label, forecast, PATTERN_SOC_WHOLE, SOC_UNAVAILABLE)
        binding.reachDetail.text = arrivalRoute.orEmpty()
    }

    /**
     * Reads the guidance off the main thread, at the Details page's own pace: a route shortens by
     * metres a second and this is not a speedometer. The rate is the one the Details page uses —
     * the driver's recorded trips, or the car's own range when they do not vouch for one.
     */
    private fun refreshArrival(value: EnergySnapshot) {
        if (value.timestampMs - arrivalReadAtMs < ARRIVAL_REFRESH_MS) return
        arrivalReadAtMs = value.timestampMs
        val trips = recentTrips
        runCatching {
            background.execute {
                SaicNavGuidance.connect(applicationContext)
                val generation = FirmwareInfo.getGeneration()
                val guidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
                val remainingKm = guidance?.remainingDistanceKm(generation)
                if (guidance == null || remainingKm == null) {
                    arrival = null
                    return@execute
                }
                val socPercent = value.socPercent?.toDouble()
                val rate = SocRateEstimator.fromTrips(trips, generation)
                    ?: SocRateEstimator.fromVehicleRange(socPercent, value.rangeKm?.toDouble())
                arrivalRoute = getString(
                    R.string.reach_route_detail,
                    format("%.0f", remainingKm),
                    guidance.remainingMinutes?.toString() ?: DASH,
                )
                arrival = ArrivalSocForecast.of(socPercent, remainingKm, rate)
            }
        }
    }

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
                val trips = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
                val summaries = trips.map { it.summary }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    recentTrips = summaries
                    recorder?.latest?.let(::render)
                }
            }
        }
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
        const val ARRIVAL_REFRESH_MS = 5_000L
    }
}
