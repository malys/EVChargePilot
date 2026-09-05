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
import com.evsuite.chargepilot.route.OpenChargeMap
import com.evsuite.chargepilot.route.OrsDirections
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RouteGeometry
import com.evsuite.chargepilot.route.RouteWhatIf
import com.evsuite.chargepilot.route.RoutingCredentials
import com.evsuite.chargepilot.route.RoutingTransport
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.ChargeStopPlan
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.RouteGrade
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.SocRateEstimator
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
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
 * OpenRouteService with the driver's own key (CP-043, CP-047). At most three requests per plan:
 * one to turn what was typed into coordinates, one for the route, and — only when a stop is
 * needed and a charger key is configured — one for the chargers near where that stop falls
 * (CP-048). All of them are driver actions; nothing here is on a timer, because 2000 requests a
 * day is generous for a driver and an afternoon for a clock.
 *
 * Parked-only. It takes a keyboard, and the answer is a decision about a trip rather than
 * something to read at 110.
 */
class ChargeStopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargeStopBinding

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-charge-stop")
    }

    /** One per service: three separate allowances, and a shared counter would refuse the wrong call. */
    private val directions = RoutingTransport()
    private val geocode = RoutingTransport(OrsGeocode.quota())
    private val chargers = RoutingTransport(OpenChargeMap.quota())

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null

    @Volatile
    private var snapshot: EnergySnapshot? = null

    @Volatile
    private var rate: SocRate? = null

    /**
     * CP-052's fit, in percent of charge per 100 km. Null until enough of the driver's own
     * road has been recorded to determine one.
     */
    @Volatile
    private var model: SocConsumptionModel? = null

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
        binding.vehicleSettingsAction.setOnClickListener {
            startActivity(Intent(this, VehicleSettingsActivity::class.java))
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
            EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
        }.getOrDefault(emptyList())
        rate = SocRateEstimator.fromTrips(trips.map { it.summary }, generation)
        // Fitted from the charge gauge and the speed, because this car publishes no battery
        // power and the kWh model therefore never trains on it (CP-052). Refitted on each
        // load rather than stored: it is a few thousand samples of arithmetic on a worker
        // thread, and a cache would only add a way for the two to disagree.
        model = (SocConsumptionFitter().fit(trips, generation) as? SocConsumptionFitResult.Ready)
            ?.model
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
        // The driver's own figures (CP-054), read on the main thread because they are four
        // numbers in a preferences file and the worker below must not race the settings screen.
        val settings = VehicleSettings.read(this)
        val effective = rate ?: SocRateEstimator.fromVehicleRange(
            socPercent,
            snapshot?.rangeKm?.toDouble(),
        )
        announce(getString(R.string.charge_stop_routing))
        worker.execute {
            val body = OrsDirections.requestBody(
                OrsDirections.Point(origin.longitude, origin.latitude, origin.altitudeMetres),
                OrsDirections.Point(place.longitude, place.latitude, null),
                alternatives = ALTERNATIVES,
            )
            val result = directions.post(credentials, OrsDirections.PATH, body)
            val routes = (result as? RoutingTransport.Result.Ok)
                ?.let { OrsDirections.parse(it.body) }.orEmpty()
            val route = routes.firstOrNull()
            // The road ahead, which is the only elevation a forecast can use: CP-031 refused
            // the altitude behind the car and that refusal stands.
            val grade = route?.let {
                RouteGrade.of(it.ascentMetres, it.descentMetres, settings.pack)
            }
            val plan = route?.let {
                ChargeStopPlan.of(
                    socPercent,
                    it.distanceKm,
                    effective,
                    settings.reservePercent,
                    grade,
                )
            }
            val stop = plan as? ChargeStopPlan.Plan.Stop
            val charger = if (route != null && stop != null) {
                findCharger(route, stop.afterKm, socPercent, effective, settings)
            } else {
                null
            }
            // Arithmetic over what already came back — no second request, and on the worker
            // thread the route arrived on.
            val whatIf = route?.let {
                RouteWhatIf.slower(
                    it.sections,
                    model,
                    snapshot?.outsideTempCelsius?.toDouble(),
                    // Only a plan that has a stop can have it removed. Without the guard the
                    // headline would announce a stop avoided on a route that never had one.
                ) { saved ->
                    stop != null &&
                        removesStop(socPercent, saved, it.distanceKm, effective, grade, settings)
                }
            }
            val alternative = route?.let { best ->
                routes.getOrNull(1)?.let {
                    RouteWhatIf.alternative(best, it, effective, settings.pack)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(place, route, plan, effective, charger, grade, whatIf, alternative)
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

    /**
     * Would this much charge, freed by slowing down, remove the stop.
     *
     * The planner answers it rather than the what-if: the reserve, the band and the refusal
     * rules live in one place, and a second implementation of them would drift.
     */
    private fun removesStop(
        socPercent: Double?,
        savedPercent: Double,
        routeKm: Double,
        rate: SocRate?,
        grade: RouteGrade.Cost?,
        settings: VehicleSettings.Values,
    ): Boolean {
        if (socPercent == null) return false
        val freed = (socPercent + savedPercent).coerceAtMost(100.0)
        return ChargeStopPlan.of(freed, routeKm, rate, settings.reservePercent, grade) is
            ChargeStopPlan.Plan.NoStop
    }

    /**
     * The last charger reachable before the reserve floor, on the worker thread that already
     * has the route.
     *
     * Only the stretch of road where a stop could fall is sent — [OpenChargeMap.WINDOW_KM] of it
     * — and never the trip. The charger service has no business knowing where the driver started
     * or where they are going, and CP-048 is where that boundary is argued.
     */
    private fun findCharger(
        route: OrsDirections.Route,
        afterKm: Double,
        socPercent: Double?,
        rate: SocRate?,
        settings: VehicleSettings.Values,
    ): Found? {
        val credentials = RoutingCredentials.readCharger(this) ?: return null
        val window = RouteGeometry.window(
            route.points,
            (afterKm - OpenChargeMap.WINDOW_KM).coerceAtLeast(0.0),
            afterKm,
        )
        if (window.size < 2) return null
        val result = chargers.get(credentials, OpenChargeMap.PATH, OpenChargeMap.query(window))
        val body = (result as? RoutingTransport.Result.Ok)?.body ?: return null
        return OpenChargeMap.parse(body, settings.minChargerPowerKw)
            .mapNotNull { charger ->
                val alongKm = RouteGeometry.distanceAlongKm(
                    route.points,
                    charger.longitude,
                    charger.latitude,
                    OpenChargeMap.CORRIDOR_KM,
                ) ?: return@mapNotNull null
                if (alongKm > afterKm) return@mapNotNull null
                val arrival = ChargeStopPlan.of(
                    socPercent,
                    alongKm,
                    rate,
                    settings.reservePercent,
                ) as? ChargeStopPlan.Plan.NoStop
                Found(charger, alongKm, arrival?.arrivalPercent)
            }
            // The last one before the floor, not the nearest: the nearest wastes the range the
            // car still has, and this is the one the ticket asked for.
            .maxByOrNull { it.alongKm }
    }

    private fun render(
        place: OrsGeocode.Place,
        route: OrsDirections.Route?,
        plan: ChargeStopPlan.Plan?,
        rate: SocRate?,
        charger: Found?,
        grade: RouteGrade.Cost?,
        whatIf: RouteWhatIf.Result?,
        alternative: RouteWhatIf.Alternative?,
    ) {
        if (route == null || plan == null) {
            binding.chargeStopPlan.visibility = View.GONE
            binding.chargeStopDetail.visibility = View.GONE
            binding.chargeStopAttribution.visibility = View.GONE
            binding.chargerPlace.visibility = View.GONE
            binding.chargerSource.visibility = View.GONE
            binding.routeWhatIf.visibility = View.GONE
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
        val detail = getString(
            R.string.charge_stop_detail,
            place.label,
            format(route.distanceKm, "%.1f"),
            route.durationMinutes.toInt(),
            route.ascentMetres?.let { format(it, "%.0f") } ?: getString(R.string.value_unavailable),
            route.descentMetres?.let { format(it, "%.0f") } ?: getString(R.string.value_unavailable),
            rate?.let { format(it.percentPerKm, "%.3f") } ?: getString(R.string.value_unavailable),
        )
        // Its own line, and only when there is a profile: no line at all says "not known",
        // where a zero would say "flat road".
        val gradeLine = grade?.let {
            getString(
                R.string.charge_stop_grade,
                format(it.percent, "%+.1f"),
                format(it.uncertaintyPercent, "%.1f"),
            )
        }
        binding.chargeStopDetail.text = listOfNotNull(detail, gradeLine).joinToString("\n")

        renderCharger(plan, charger)
        renderWhatIf(whatIf, alternative)

        // ORS routes are OpenStreetMap under ODbL. Showing this is the licence, not a courtesy.
        binding.chargeStopAttribution.visibility =
            if (route.attribution == null) View.GONE else View.VISIBLE
        binding.chargeStopAttribution.text = route.attribution
    }

    /**
     * What driving differently would change, with its price attached.
     *
     * One card, and never a saving on its own: a row that said "saves 6 %" without the twenty
     * minutes it costs would be advice, and this screen does not give advice. The headline goes
     * first because it is the only line that changes a decision — everything under it is the
     * table it came from.
     */
    private fun renderWhatIf(whatIf: RouteWhatIf.Result?, alternative: RouteWhatIf.Alternative?) {
        val lines = ArrayList<String>()
        when (whatIf) {
            null -> Unit
            is RouteWhatIf.Result.Unavailable -> lines.add(
                getString(
                    when (whatIf.reason) {
                        SpeedWhatIfUnavailable.MODEL_NOT_TRAINED ->
                            R.string.route_what_if_unavailable_model
                        SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE ->
                            R.string.route_what_if_unavailable_envelope
                        SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION ->
                            R.string.route_what_if_unavailable_road
                        // The remaining reasons are the post-trip comparison's, and reading
                        // them as a missing temperature is what they amount to here.
                        else -> R.string.route_what_if_unavailable_temperature
                    }
                )
            )
            is RouteWhatIf.Result.Ready -> {
                whatIf.headline?.let {
                    lines.add(
                        getString(
                            R.string.route_what_if_headline,
                            it.speedKmh,
                            it.delayMinutes.toInt(),
                        )
                    )
                }
                whatIf.options.mapTo(lines) {
                    getString(
                        R.string.route_what_if_row,
                        it.speedKmh,
                        format(it.affectedKm, "%.0f"),
                        it.delayMinutes.toInt(),
                        format(it.savedPercentLow, "%+.1f"),
                        format(it.savedPercentHigh, "%+.1f"),
                    )
                }
            }
        }
        alternative?.let {
            val distance = format(it.distanceDeltaKm, "%+.0f")
            val delay = format(it.delayMinutes, "%+.0f")
            val low = format(it.savedPercentLow, "%+.1f")
            val high = format(it.savedPercentHigh, "%+.1f")
            lines.add(
                it.viaLabel?.let { road ->
                    getString(R.string.route_what_if_alternative, road, distance, delay, low, high)
                } ?: getString(
                    R.string.route_what_if_alternative_unnamed, distance, delay, low, high
                )
            )
        }
        // CP-005: nothing here was measured, and the card says so under the figures rather
        // than repeating it on every row.
        if (whatIf is RouteWhatIf.Result.Ready || alternative != null) {
            lines.add(getString(R.string.route_what_if_source))
        }
        binding.routeWhatIf.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.routeWhatIf.text = lines.joinToString("\n")
    }

    /**
     * Where to stop, and how much to trust it.
     *
     * A charger record is never rendered as a fact. Its provider and the date it was last
     * confirmed are on their own line under it, because a dataset is wrong the day it is
     * published and the driver is the one who finds out.
     */
    private fun renderCharger(plan: ChargeStopPlan.Plan, charger: Found?) {
        if (plan !is ChargeStopPlan.Plan.Stop) {
            binding.chargerPlace.visibility = View.GONE
            binding.chargerSource.visibility = View.GONE
            return
        }
        binding.chargerPlace.visibility = View.VISIBLE
        if (charger == null) {
            binding.chargerSource.visibility = View.GONE
            binding.chargerPlace.text = if (RoutingCredentials.isChargerConfigured(this)) {
                getString(R.string.charge_stop_charger_none)
            } else {
                getString(R.string.charge_stop_charger_absent)
            }
            return
        }
        val place = getString(
            R.string.charge_stop_charger,
            charger.charger.name,
            format(charger.alongKm, "%.0f"),
            charger.charger.powerKw?.let { format(it, "%.0f") }
                ?: getString(R.string.value_unavailable),
            charger.charger.connectors.joinToString(", "),
        )
        val arrival = charger.arrivalPercent
            ?.let { getString(R.string.charge_stop_charger_arrival, format(it, "%.0f")) }
        val working = if (charger.charger.operational == false) {
            getString(R.string.charge_stop_charger_not_operational)
        } else {
            null
        }
        binding.chargerPlace.text = listOfNotNull(place, arrival, working).joinToString("\n")

        // Open Charge Map's terms require the data provider's own attribution, visible to the
        // driver: half the dataset is imported and is not OCM's to license.
        val provider = charger.charger.operator
            ?: charger.charger.dataProvider
            ?: getString(R.string.value_unavailable)
        binding.chargerSource.visibility = View.VISIBLE
        binding.chargerSource.text = charger.charger.verifiedAt?.let {
            getString(R.string.charge_stop_charger_source, provider, it.take(10))
        } ?: getString(R.string.charge_stop_charger_source_undated, provider)
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

    /** A charger, where it falls on this route, and the charge left on reaching it. */
    private data class Found(
        val charger: OpenChargeMap.Charger,
        val alongKm: Double,
        val arrivalPercent: Double?,
    )

    private companion object {
        const val HISTORY_FILE = "trips.json"

        /**
         * How many routes to ask ORS for. One request either way, so the second road costs the
         * driver's quota nothing; only the first is ever planned on.
         */
        const val ALTERNATIVES = 2

    }
}
