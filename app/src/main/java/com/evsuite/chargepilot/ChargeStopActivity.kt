package com.evsuite.chargepilot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityChargeStopBinding
import com.evsuite.chargepilot.route.LocationSource
import com.evsuite.chargepilot.route.OrsDirections
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RoutingCredentials
import com.evsuite.chargepilot.route.RoutingTransport
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.ChargeStopPlan
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.SocRateEstimator
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The sentence this whole project was described by: *do I need to charge on the way, and in how
 * many kilometres.*
 *
 * The arithmetic is [ChargeStopPlan] in EVHardware, where it is JVM-testable and has no idea a
 * network exists. This screen supplies its three inputs and nothing more: the charge now, the
 * charge spent per kilometre from the driver's own trips, and a route length.
 *
 * The route is the part the car will not give. CP-040 proved the head unit publishes a remaining
 * distance and nothing else — no shape, no elevation — so a route with a profile comes from
 * OpenRouteService with the driver's own key (CP-043, CP-047). Two requests per plan: one to turn
 * what was typed into coordinates, one for the route. Both are driver actions; nothing here is
 * on a timer, because 2000 requests a day is generous for a driver and an afternoon for a clock.
 *
 * Parked-only. It takes a keyboard, and the answer is a decision about a trip rather than
 * something to read at 110.
 */
class ChargeStopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargeStopBinding

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-charge-stop")
    }

    /** Two, because ORS counts directions and geocoding against separate allowances. */
    private val directions = RoutingTransport()
    private val geocode = RoutingTransport(OrsGeocode.quota())

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null

    @Volatile
    private var snapshot: EnergySnapshot? = null

    @Volatile
    private var rate: SocRate? = null

    private var places: List<OrsGeocode.Place> = emptyList()

    /** The place waiting for a location grant, so a granted prompt continues what was asked. */
    private var pending: OrsGeocode.Place? = null

    private var message: String? = null

    private val gateExpiry = Runnable { render() }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val place = pending
        pending = null
        // The result map is not the answer: on this platform a permission from an already held
        // group can be granted without a prompt, and a coarse-only grant is a refusal of what
        // was asked for. What counts is whether fine location is held now.
        if (place != null && LocationSource.hasPrecise(this)) route(place) else render()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@ChargeStopActivity) { latest ->
                snapshot = latest
                speedKmh = latest.speedKmh
                speedObservedAtMs = latest.timestampMs
                render()
            }
            snapshot = value.latest
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            snapshot = null
            speedKmh = null
            speedObservedAtMs = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChargeStopBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.routingSettingsAction.setOnClickListener {
            startActivity(Intent(this, RoutingSettingsActivity::class.java))
        }
        binding.searchAction.setOnClickListener { search() }
        worker.execute { loadRate() }
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        render()
    }

    override fun onStop() {
        recorder?.clearListener(this)
        recorder = null
        binding.root.removeCallbacks(gateExpiry)
        if (bound) unbindService(connection)
        bound = false
        snapshot = null
        speedKmh = null
        speedObservedAtMs = null
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /** The rate this driver has actually spent; the vehicle's own range when there are no trips. */
    private fun loadRate() {
        val generation = FirmwareInfo.getGeneration()
        val trips = runCatching {
            EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).readSummaries()
        }.getOrDefault(emptyList())
        rate = SocRateEstimator.fromTrips(trips, generation)
    }

    private fun search() {
        val text = binding.destinationInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            announce(getString(R.string.charge_stop_destination_missing))
            return
        }
        val credentials = RoutingCredentials.read(this)
        if (credentials == null) {
            announce(getString(R.string.charge_stop_not_configured))
            return
        }
        val near = LocationSource.lastKnown(this)
        announce(getString(R.string.charge_stop_searching))
        worker.execute {
            val result = geocode.get(credentials, OrsGeocode.PATH, OrsGeocode.query(text, near))
            val found = (result as? RoutingTransport.Result.Ok)?.let { OrsGeocode.parse(it.body) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                places = found.orEmpty()
                showPlaces()
                announce(
                    when {
                        result is RoutingTransport.Result.Refused -> refusal(result)
                        places.isEmpty() -> getString(R.string.charge_stop_no_results)
                        else -> getString(R.string.charge_stop_choose)
                    }
                )
            }
        }
    }

    /** One button per answer: a head unit list the driver reads once and taps once. */
    private fun showPlaces() {
        binding.destinationResults.removeAllViews()
        places.forEach { place ->
            val button = layoutInflater.inflate(
                R.layout.row_destination_result, binding.destinationResults, false
            ) as MaterialButton
            button.text = place.label
            button.setOnClickListener { route(place) }
            binding.destinationResults.addView(button)
        }
    }

    private fun route(place: OrsGeocode.Place) {
        val credentials = RoutingCredentials.read(this) ?: run {
            announce(getString(R.string.charge_stop_not_configured))
            return
        }
        if (!LocationSource.hasPrecise(this)) {
            pending = place
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }
        val origin = LocationSource.lastKnown(this)
        if (origin == null) {
            announce(getString(R.string.charge_stop_no_position))
            return
        }
        val socPercent = snapshot?.socPercent?.toDouble()
        val effective = rate ?: SocRateEstimator.fromVehicleRange(
            socPercent,
            snapshot?.rangeKm?.toDouble(),
        )
        announce(getString(R.string.charge_stop_routing))
        worker.execute {
            val body = OrsDirections.requestBody(
                OrsDirections.Point(origin.longitude, origin.latitude, origin.altitudeMetres),
                OrsDirections.Point(place.longitude, place.latitude, null),
            )
            val result = directions.post(credentials, OrsDirections.PATH, body)
            val route = (result as? RoutingTransport.Result.Ok)
                ?.let { OrsDirections.parse(it.body).firstOrNull() }
            val plan = route?.let { ChargeStopPlan.of(socPercent, it.distanceKm, effective) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(place, route, plan, effective)
                announce(
                    when {
                        result is RoutingTransport.Result.Refused -> refusal(result)
                        route == null -> getString(R.string.charge_stop_no_route)
                        else -> getString(R.string.charge_stop_ready)
                    }
                )
            }
        }
    }

    private fun render(
        place: OrsGeocode.Place,
        route: OrsDirections.Route?,
        plan: ChargeStopPlan.Plan?,
        rate: SocRate?,
    ) {
        if (route == null || plan == null) {
            binding.chargeStopPlan.visibility = View.GONE
            binding.chargeStopDetail.visibility = View.GONE
            binding.chargeStopAttribution.visibility = View.GONE
            return
        }
        binding.chargeStopPlan.visibility = View.VISIBLE
        binding.chargeStopPlan.text = when (plan) {
            is ChargeStopPlan.Plan.NoStop -> getString(
                R.string.charge_stop_plan_none,
                format(plan.arrivalPercent, "%.0f"),
                format(plan.marginPercent, "%.0f"),
            )
            is ChargeStopPlan.Plan.Stop -> getString(
                R.string.charge_stop_plan_stop,
                format(plan.afterKm, "%.0f"),
                format(plan.bandKm, "%.0f"),
            )
            is ChargeStopPlan.Plan.Refused -> getString(
                when (plan.reason) {
                    ChargeStopPlan.Reason.NO_CHARGE -> R.string.charge_stop_refused_charge
                    ChargeStopPlan.Reason.NO_ROUTE -> R.string.charge_stop_refused_route
                    ChargeStopPlan.Reason.NO_RATE -> R.string.charge_stop_refused_rate
                    ChargeStopPlan.Reason.BAND_TOO_WIDE -> R.string.charge_stop_refused_band
                }
            )
        }

        binding.chargeStopDetail.visibility = View.VISIBLE
        binding.chargeStopDetail.text = getString(
            R.string.charge_stop_detail,
            place.label,
            format(route.distanceKm, "%.1f"),
            route.durationMinutes.toInt(),
            route.ascentMetres?.let { format(it, "%.0f") } ?: getString(R.string.value_unavailable),
            route.descentMetres?.let { format(it, "%.0f") } ?: getString(R.string.value_unavailable),
            rate?.let { format(it.percentPerKm, "%.3f") } ?: getString(R.string.value_unavailable),
        )

        // ORS routes are OpenStreetMap under ODbL. Showing this is the licence, not a courtesy.
        binding.chargeStopAttribution.visibility =
            if (route.attribution == null) View.GONE else View.VISIBLE
        binding.chargeStopAttribution.text = route.attribution
    }

    /**
     * A refusal the driver can act on. The detail travels only where it means something to
     * them — seconds to wait, a status code — never the transport's own English reason string.
     */
    private fun refusal(result: RoutingTransport.Result.Refused): String = when (result.reason) {
        RoutingTransport.Reason.NOT_CONFIGURED -> getString(R.string.charge_stop_not_configured)
        RoutingTransport.Reason.BUSY -> getString(R.string.routing_refused_busy)
        RoutingTransport.Reason.QUOTA_MINUTE ->
            getString(R.string.routing_refused_quota_minute, result.detail.orEmpty())
        RoutingTransport.Reason.QUOTA_DAY -> getString(R.string.routing_refused_quota_day)
        RoutingTransport.Reason.TRANSPORT -> getString(R.string.routing_refused_transport)
        RoutingTransport.Reason.SERVER_DAILY_LIMIT -> getString(R.string.routing_refused_server_day)
        RoutingTransport.Reason.SERVER_RATE_LIMIT ->
            getString(R.string.routing_refused_server_minute)
        RoutingTransport.Reason.SERVER_REJECTED ->
            getString(R.string.routing_refused_server, result.detail.orEmpty())
        RoutingTransport.Reason.UNREADABLE -> getString(R.string.routing_refused_unreadable)
    }

    private fun announce(text: String) {
        message = text
        render()
    }

    private fun render() {
        binding.root.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)
        if (gate == ParkedDeletionGate.PARKED) {
            val ageMs = nowMs - checkNotNull(speedObservedAtMs)
            binding.root.postDelayed(
                gateExpiry,
                ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
            )
        }
        val usable = gate == ParkedDeletionGate.PARKED
        binding.destinationLayout.isEnabled = usable
        binding.searchAction.isEnabled = usable
        binding.destinationResults.isEnabled = usable
        for (index in 0 until binding.destinationResults.childCount) {
            binding.destinationResults.getChildAt(index).isEnabled = usable
        }

        binding.chargeStopStatus.text = message ?: when (gate) {
            ParkedDeletionGate.MOVING -> getString(R.string.charge_stop_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE ->
                getString(R.string.charge_stop_speed_unavailable)
            ParkedDeletionGate.PARKED ->
                if (RoutingCredentials.isConfigured(this)) getString(R.string.charge_stop_idle)
                else getString(R.string.charge_stop_not_configured)
        }
        message = null
    }

    private fun format(value: Double, pattern: String): String =
        String.format(Locale.getDefault(), pattern, value)

    private companion object {
        const val HISTORY_FILE = "trips.json"
    }
}
