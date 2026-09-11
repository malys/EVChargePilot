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
import com.evsuite.hardware.saic.NavigationHandoff
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.ChargeStopPlan
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.PlanDrift
import com.evsuite.hardware.telemetry.RouteGrade
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.SocRateEstimator
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
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

    /** The place waiting for a location grant, so a granted prompt continues what was asked. */
    private var pending: OrsGeocode.Place? = null

    /** True while the receiver is being asked for a fix, so a second tap does not ask again. */
    private var locating = false

    private var message: String? = null

    /** The plan the handoff button would send, set by the render that offered it. */
    private var handoff: Handoff? = null

    /**
     * CP-058's plan, assembled by the render that offered the button and written down by the
     * tap. Null whenever the drive it describes could not be followed — no route, no rate, or a
     * stop with no charger found, which is a plan that already says it does not work.
     */
    private var followed: FollowedPlan.Values? = null

    private val gateExpiry = Runnable { render() }

    /**
     * The chosen destination, coming back from the screen that owns choosing one.
     *
     * A cancel is silence: the driver went to look and came back, and a plan they never asked
     * for would be three requests they did not spend.
     */
    private val destination = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val place = DestinationActivity.place(result.data) ?: return@registerForActivityResult
        binding.chooseDestinationAction.text =
            getString(R.string.charge_stop_action_destination_chosen, place.label)
        route(place)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val place = pending
        pending = null
        // The result map is not the answer: on this platform a permission from an already held
        // group can be granted without a prompt, and a coarse-only grant is a refusal of what
        // was asked for. What counts is whether fine location is held now.
        val granted = LocationSource.hasPrecise(this)
        ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
            "permission result: fine=$granted, and the request " +
                if (place != null) "continues the route that asked for it" else "had nothing waiting"
        }
        when {
            place == null -> render()
            granted -> route(place)
            // A refusal — or a system that answered without ever showing a dialog, which this
            // head unit does when another permission in the LOCATION group is already held —
            // used to end here in silence. The driver tapped a destination and nothing at all
            // happened, which reads as a broken button rather than as a missing grant.
            else -> announce(getString(R.string.charge_stop_location_refused))
        }
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
        binding.chooseDestinationAction.setOnClickListener {
            // The drive of 2026-09-09 came back with "nothing at all happens and it loops back
            // to the destination window". This line is how the next bundle tells the two
            // readings apart: a driver reaching for guidance and hitting the chooser instead,
            // or a tap on the handoff that really did nothing. It fires only when a plan was
            // on screen — choosing a second destination before planning one is not the bug.
            if (handoff != null) {
                ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
                    "the destination chooser was tapped while a plan and its handoff were on " +
                        "screen: the chooser reopens and no handoff is sent"
                }
            }
            destination.launch(DestinationActivity.intent(this))
        }
        binding.backAction.setOnClickListener { finish() }
        binding.navigateAction.setOnClickListener { navigate() }
        // Bound here rather than at the tap: the bind is asynchronous, and a driver who plans a
        // route and hands it over has given it the whole of that time to come up. Read-only
        // until the tap — nothing registers a listener on this screen.
        SaicNavGuidance.connect(applicationContext)
        // Two vehicle sessions came back with Q3 to Q8 and Q10 blank, and the bundle could not
        // say why: every one of them fires downstream of a route, and no route was ever asked
        // for. This line fires where the driver arrives, so the next bundle names what was
        // missing instead of being silent about it. Booleans only — never a key, never a place.
        ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
            "charge-stop screen opened: fine=${LocationSource.hasPrecise(this)}" +
                ", fix=${LocationSource.lastKnown(this) != null}" +
                ", routing key=${RoutingCredentials.isConfigured(this)}" +
                ", charger key=${RoutingCredentials.isChargerConfigured(this)}"
        }
        worker.execute { loadRate() }
        render()
        // CP-062. This screen is no longer a page of its own: the driver asks for a destination
        // on the dashboard, and the plan for that destination is what opens. The chooser stays
        // in the top bar, so a second destination does not mean going back first.
        DestinationActivity.place(intent)?.let { place ->
            binding.chooseDestinationAction.text =
                getString(R.string.charge_stop_action_destination_chosen, place.label)
            // Before the route, not after it. A destination is already somewhere the map can
            // open at, and every path in [route] that returns before a route comes back — no
            // routing key, no fine grant, no fix — used to leave the screen with nothing to
            // send and the chooser as its only enabled control. The route replaces this
            // hand-off with the planned one as soon as there is one.
            renderUnplannedHandoff(place, R.string.charge_stop_navigate_planning)
            route(place)
        }
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
        val fit = SocConsumptionFitter().fit(trips, generation)
        model = (fit as? SocConsumptionFitResult.Ready)?.model
        ValidationProbe.record(ValidationQuestion.SOC_SEGMENTS) {
            val head = "trips=${trips.size} samples=${trips.sumOf { it.samples?.size ?: 0 }} " +
                "rate=${rate?.let { r -> probeFormat(r.percentPerKm, "%.3f") + " %/km" } ?: "none"}"
            when (fit) {
                is SocConsumptionFitResult.Ready -> fit.model.let { m ->
                    "$head fit=ready segments=${m.segmentCount} " +
                        "speed=${probeFormat(m.envelope.minSpeedKmh, "%.0f")}..${
                            probeFormat(m.envelope.maxSpeedKmh, "%.0f")} km/h " +
                        "temp=${probeFormat(m.envelope.minOutsideTempCelsius, "%.0f")}..${
                            probeFormat(m.envelope.maxOutsideTempCelsius, "%.0f")} C " +
                        "rmse=${probeFormat(m.residualRmsePercentPer100Km, "%.2f")} %/100 km"
                }
                is SocConsumptionFitResult.Unavailable -> "$head fit=unavailable(${fit.reason})"
            }
        }
    }

    private fun route(place: OrsGeocode.Place) {
        val credentials = RoutingCredentials.read(this) ?: run {
            announce(getString(R.string.charge_stop_not_configured))
            return
        }
        if (!LocationSource.hasPrecise(this)) {
            ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
                "route asked without a fine grant: the prompt is being requested"
            }
            pending = place
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }
        val cached = LocationSource.lastKnown(this)
        if (cached != null) {
            ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
                // Age and altitude, never the position itself: this file leaves the car.
                "fix held: age=${cached.ageMs / 1000}s altitude=${cached.altitudeMetres != null}"
            }
            routeFrom(place, credentials, cached)
            return
        }
        // One request at a time. The 2026-09-07 session recorded fifteen refusals in eighteen
        // seconds — a driver tapping a screen that had told them nothing was happening.
        if (locating) return
        locating = true
        ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
            "no cached fix within ${LocationSource.MAX_AGE_MS / 1000}s: the GPS is being asked"
        }
        announce(getString(R.string.charge_stop_locating))
        LocationSource.requestCurrent(this) { fix ->
            locating = false
            if (isFinishing || isDestroyed) return@requestCurrent
            ValidationProbe.record(ValidationQuestion.LOCATION_FALLBACK) {
                fix?.let {
                    "fix from the receiver: age=${it.ageMs / 1000}s " +
                        "altitude=${it.altitudeMetres != null}"
                } ?: "nothing after ${LocationSource.FIX_TIMEOUT_MS / 1000}s of receiver: refused"
            }
            if (fix == null) announce(getString(R.string.charge_stop_no_position))
            else routeFrom(place, credentials, fix)
        }
    }

    /** The route itself, once there is somewhere to start from. */
    private fun routeFrom(
        place: OrsGeocode.Place,
        credentials: RoutingCredentials.Values,
        origin: LocationSource.Fix,
    ) {
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
            ValidationProbe.record(ValidationQuestion.ROUTE_ALTERNATIVES) {
                "asked alternatives=$ALTERNATIVES, " + when (result) {
                    is RoutingTransport.Result.Ok ->
                        "answered ${result.body.length} chars, parsed ${routes.size} route(s)"
                    is RoutingTransport.Result.Refused -> "refused(${result.reason})"
                }
            }
            ValidationProbe.record(ValidationQuestion.ROUTE_SECTIONS) {
                route?.let { r ->
                    "route ${probeFormat(r.distanceKm, "%.1f")} km in " +
                        "${probeFormat(r.durationMinutes, "%.0f")} min, ${r.sections.size} section(s): " +
                        r.sections.take(VALIDATION_SECTIONS).joinToString(" | ") { section ->
                            "${section.road ?: "unnamed"} ${probeFormat(section.distanceKm, "%.1f")} km " +
                                "@${section.impliedSpeedKmh?.let { v -> probeFormat(v, "%.0f") } ?: "?"}"
                        }
                } ?: "no route parsed"
            }
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
            // CP-057's third row, and the only extra request this screen ever makes. It is worth
            // the driver's quota only when the planned road needs a stop and this one might not:
            // an hour of departmental roads to arrive with the same charge is not a choice.
            val motorwayFree = if (route != null && stop != null) {
                motorwayFree(credentials, origin, place, socPercent, effective, settings)
            } else {
                null
            }
            ValidationProbe.record(ValidationQuestion.WHAT_IF) {
                val body = when (whatIf) {
                    null -> "no route, so no what-if"
                    is RouteWhatIf.Result.Unavailable -> "unavailable(${whatIf.reason})"
                    is RouteWhatIf.Result.Ready -> "ready rows=${whatIf.options.size} " +
                        "headline=${whatIf.headline?.speedKmh?.toString() ?: "none"} " +
                        whatIf.options.joinToString(" | ") { option ->
                            "${option.speedKmh}: ${probeFormat(option.affectedKm, "%.0f")} km " +
                                "${probeFormat(option.delayMinutes, "%.0f")} min " +
                                "${probeFormat(option.savedPercentLow, "%.1f")}..${
                                    probeFormat(option.savedPercentHigh, "%.1f")} %"
                        }
                }
                "$body | second road=${alternative?.viaLabel ?: "none"}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(
                    place, route, plan, effective, charger, grade, whatIf, alternative,
                    motorwayFree, if (motorwayFree == null) 1 else 2,
                )
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
    /**
     * The same trip on roads that are not motorways, planned but not searched for chargers.
     *
     * One ORS request and no Open Charge Map request: what makes this row a choice is whether it
     * needs a stop at all, and [ChargeStopPlan] answers that from the distance alone. Finding a
     * charger for a road the driver has not picked would spend a second allowance on a row they
     * are about to dismiss.
     *
     * A refusal here is not a refusal of the plan. The two motorway rows stand on their own and
     * this one is simply absent, with a line saying so.
     */
    private fun motorwayFree(
        credentials: RoutingCredentials.Values,
        origin: LocationSource.Fix,
        place: OrsGeocode.Place,
        socPercent: Double?,
        rate: SocRate?,
        settings: VehicleSettings.Values,
    ): MotorwayFree? {
        val body = OrsDirections.requestBody(
            OrsDirections.Point(origin.longitude, origin.latitude, origin.altitudeMetres),
            OrsDirections.Point(place.longitude, place.latitude, null),
            avoidMotorways = true,
        )
        val result = directions.post(credentials, OrsDirections.PATH, body)
        val road = (result as? RoutingTransport.Result.Ok)
            ?.let { OrsDirections.parse(it.body) }?.firstOrNull()
        ValidationProbe.record(ValidationQuestion.ROUTE_ALTERNATIVES) {
            "motorway-free road: " + when {
                result is RoutingTransport.Result.Refused -> "refused(${result.reason})"
                road == null -> "answered, nothing parsed"
                else -> "${probeFormat(road.distanceKm, "%.1f")} km in " +
                    "${probeFormat(road.durationMinutes, "%.0f")} min via ${road.viaLabel ?: "?"}"
            }
        }
        val chosen = road ?: return null
        val grade = RouteGrade.of(chosen.ascentMetres, chosen.descentMetres, settings.pack)
        return MotorwayFree(
            route = chosen,
            plan = ChargeStopPlan.of(
                socPercent,
                chosen.distanceKm,
                rate,
                settings.reservePercent,
                grade,
            ),
        )
    }

    private fun findCharger(
        route: OrsDirections.Route,
        afterKm: Double,
        socPercent: Double?,
        rate: SocRate?,
        settings: VehicleSettings.Values,
    ): Found? {
        val credentials = RoutingCredentials.readCharger(this) ?: run {
            ValidationProbe.record(ValidationQuestion.CHARGERS) { "no charger key configured" }
            return null
        }
        val window = RouteGeometry.window(
            route.points,
            (afterKm - OpenChargeMap.WINDOW_KM).coerceAtLeast(0.0),
            afterKm,
        )
        if (window.size < 2) {
            ValidationProbe.record(ValidationQuestion.CHARGERS) {
                "the ${OpenChargeMap.WINDOW_KM} km window before km " +
                    "${probeFormat(afterKm, "%.0f")} held ${window.size} route point(s): no search"
            }
            return null
        }
        val result = chargers.get(credentials, OpenChargeMap.PATH, OpenChargeMap.query(window))
        val body = (result as? RoutingTransport.Result.Ok)?.body ?: run {
            ValidationProbe.record(ValidationQuestion.CHARGERS) {
                "search refused(${(result as? RoutingTransport.Result.Refused)?.reason})"
            }
            return null
        }
        val found = OpenChargeMap.parse(body, settings.minChargerPowerKw)
        ValidationProbe.record(ValidationQuestion.CHARGERS) {
            val powers = found.mapNotNull { it.powerKw }
            "window ends at km ${probeFormat(afterKm, "%.0f")}, " +
                "min ${probeFormat(settings.minChargerPowerKw, "%.0f")} kW: " +
                "${found.size} charger(s), power " +
                (powers.minOrNull()?.let { low ->
                    "${probeFormat(low, "%.0f")}..${probeFormat(powers.max(), "%.0f")} kW"
                } ?: "unpublished") +
                ", operational=${found.count { it.operational == true }}"
        }
        return found
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
        motorwayFree: MotorwayFree?,
        routeRequests: Int,
    ) {
        if (route == null || plan == null) {
            binding.chargeStopPlan.visibility = View.GONE
            binding.chargeStopDetail.visibility = View.GONE
            binding.chargeStopAttribution.visibility = View.GONE
            binding.chargerPlace.visibility = View.GONE
            binding.chargerSource.visibility = View.GONE
            binding.routeWhatIf.visibility = View.GONE
            binding.routeChoices.visibility = View.GONE
            renderUnplannedHandoff(place)
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
        renderChoices(route, plan, charger, whatIf, motorwayFree, routeRequests)
        renderHandoff(place, plan, charger, route, rate, grade)

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
    /**
     * The comparison, on one card: same destination, three ways to reach it.
     *
     * Everything on this screen already answered part of "how should I drive to Alès" — the
     * plan, the charging stop, the speed what-if, the motorway-free road — and the driver was
     * doing the comparison across four cards, in their head, parked, before setting off. The
     * comparison *is* the decision, so it gets stated rather than assembled by the reader.
     *
     * **Both figures on every row, or neither.** A row that showed a charge saved without the
     * time it costs would be advice, and this screen does not give advice. A row that cannot
     * produce both says which of them is missing and why.
     *
     * The request count is on the card because the allowance is the driver's, not this app's.
     */
    private fun renderChoices(
        route: OrsDirections.Route,
        plan: ChargeStopPlan.Plan,
        charger: Found?,
        whatIf: RouteWhatIf.Result?,
        motorwayFree: MotorwayFree?,
        routeRequests: Int,
    ) {
        // Only a plan that has to choose is worth a comparison: a route the car reaches on the
        // charge it already holds has one way of being driven and no trade to make.
        if (plan !is ChargeStopPlan.Plan.Stop) {
            binding.routeChoices.visibility = View.GONE
            return
        }
        val lines = ArrayList<String>()
        lines += getString(R.string.route_choices_title)

        lines += getString(
            R.string.route_choices_planned,
            format(route.distanceKm, "%.0f"),
            format(route.durationMinutes, "%.0f"),
        ) + "\n  " + stopLine(plan, charger)

        val headline = (whatIf as? RouteWhatIf.Result.Ready)?.headline
        lines += if (headline != null) {
            getString(
                R.string.route_choices_slower,
                headline.speedKmh,
                format(route.distanceKm, "%.0f"),
                format(headline.delayMinutes, "%.0f"),
            ) + "\n  " + getString(R.string.route_choices_removes_stop)
        } else {
            getString(R.string.route_choices_slower_absent)
        }

        lines += if (motorwayFree == null) {
            getString(R.string.route_choices_no_motorway_absent)
        } else {
            val delta = motorwayFree.route.durationMinutes - route.durationMinutes
            getString(
                R.string.route_choices_no_motorway,
                format(motorwayFree.route.distanceKm, "%.0f"),
                format(motorwayFree.route.durationMinutes, "%.0f"),
                if (delta >= 0.0) getString(R.string.route_choices_delta_slower, format(delta, "%.0f"))
                else getString(R.string.route_choices_delta_faster, format(-delta, "%.0f")),
            ) + "\n  " + when (val other = motorwayFree.plan) {
                is ChargeStopPlan.Plan.NoStop -> getString(
                    R.string.route_choices_no_stop,
                    format(other.arrivalPercent, "%.0f"),
                )
                is ChargeStopPlan.Plan.Stop -> getString(
                    R.string.route_choices_stop_unknown,
                    format(other.afterKm, "%.0f"),
                )
                is ChargeStopPlan.Plan.Refused ->
                    getString(R.string.route_choices_refused, refused(other.reason))
            }
        }

        lines += getString(R.string.route_choices_requests, routeRequests)
        binding.routeChoices.visibility = View.VISIBLE
        binding.routeChoices.text = lines.joinToString("\n")
    }

    /**
     * The planned row's own consequence: where the stop falls, and the charge left on reaching it.
     *
     * The charge *after* charging is deliberately absent. It depends on how long the driver
     * plugs in, which this app does not model and must not appear to.
     */
    private fun stopLine(plan: ChargeStopPlan.Plan.Stop, charger: Found?): String {
        val arrival = charger?.arrivalPercent
        return if (arrival == null) {
            getString(R.string.route_choices_stop_unknown, format(plan.afterKm, "%.0f"))
        } else {
            getString(
                R.string.route_choices_stop,
                format(charger.alongKm, "%.0f"),
                format(arrival, "%.0f"),
            )
        }
    }

    private fun refused(reason: ChargeStopPlan.Reason): String = getString(
        when (reason) {
            ChargeStopPlan.Reason.NO_CHARGE -> R.string.charge_stop_refused_charge
            ChargeStopPlan.Reason.NO_ROUTE -> R.string.charge_stop_refused_route
            ChargeStopPlan.Reason.NO_RATE -> R.string.charge_stop_refused_rate
            ChargeStopPlan.Reason.BAND_TOO_WIDE -> R.string.charge_stop_refused_band
        }
    )

    /**
     * The hand-off a route that never came back still owes the driver.
     *
     * Without a route this screen hid the navigate bar, which left exactly one enabled control on
     * it: the chooser, whose label is the place that was just chosen. A driver reads a button
     * carrying their destination as the way to go there, taps it, and the chooser reopens — the
     * loop reported from the car on 2026-09-10, and the same misread the pinned bar was built for
     * on 2026-09-09. Hiding the only way out of a screen is what made a failed route look like a
     * broken application.
     *
     * It also took back what the fallback of 2026-09-05 promised. A destination that cannot be
     * planned is still a destination the map can open at, and the map opening is worth more to
     * someone parked than an explanation of why the routing service said no.
     *
     * The destination is all there is to send: no route means no stop to pin and no leg to
     * follow, so [followed] is cleared rather than left holding a plan for an earlier trip, and
     * the note says the car is on its own instead of the button implying a plan behind it.
     */
    private fun renderUnplannedHandoff(
        place: OrsGeocode.Place,
        noteRes: Int = R.string.charge_stop_navigate_unplanned,
    ) {
        val destination = NavigationHandoff.poi(place.latitude, place.longitude, place.label)
        handoff = Handoff(
            place.latitude, place.longitude, place.label, toStop = false,
            point = destination, destination = destination, pathway = emptyList(),
            legs = listOfNotNull(destination),
        )
        followed = null
        binding.navigateNote.visibility = View.VISIBLE
        binding.navigateNote.setText(noteRes)
    }

    /**
     * Offers to hand the plan to the car's navigation, and says what that hand-off cannot do.
     *
     * **The charging stop wins over the destination when there is one.** `geo:` carries a single
     * point, so a route planned *through* somewhere cannot be pinned by sending its endpoint —
     * the car would pick its own road and every figure on this screen would quietly become a
     * figure about a different trip. Sending the stop pins the leg the forecast is actually
     * about, and the driver sets the rest after charging. A stop with no charger found has no
     * coordinates, so that case falls back to the destination and says so.
     *
     * The caveat is a separate line, never the button's label. A button is read as a promise and
     * this one cannot make it: nothing here can stop the car choosing another road.
     */
    private fun renderHandoff(
        place: OrsGeocode.Place,
        plan: ChargeStopPlan.Plan,
        charger: Found?,
        route: OrsDirections.Route,
        rate: SocRate?,
        grade: RouteGrade.Cost?,
    ) {
        val stop = (plan as? ChargeStopPlan.Plan.Stop)?.let { charger }
        // The adapter takes the plan as it was planned: the destination, and the stop on the way
        // to it. A point that fails validation drops out of the list rather than out of the plan —
        // the geo: fallback below still has somewhere to go.
        val destination = NavigationHandoff.poi(place.latitude, place.longitude, place.label)
        val pathway = NavigationHandoff.pathway(
            listOfNotNull(
                stop?.let {
                    NavigationHandoff.poi(it.charger.latitude, it.charger.longitude, it.charger.name)
                }
            )
        )
        // The stop first and the destination after it: that is the order they are driven in, and
        // the order the chain hands them over in once each leg's guidance ends.
        val legs = pathway + listOfNotNull(destination)
        val target = if (stop != null) {
            Handoff(
                stop.charger.latitude, stop.charger.longitude, stop.charger.name, toStop = true,
                point = pathway.firstOrNull(), destination = destination, pathway = pathway,
                legs = legs,
            )
        } else {
            Handoff(
                place.latitude, place.longitude, place.label, toStop = false,
                point = destination, destination = destination, pathway = pathway,
                legs = listOfNotNull(destination),
            )
        }
        handoff = target
        followed = follow(plan, charger, route, rate, grade)
        binding.navigateNote.visibility = View.VISIBLE
        binding.navigateNote.setText(
            if (target.toStop) R.string.charge_stop_navigate_stop
            else R.string.charge_stop_navigate_destination
        )
        render()
    }

    /**
     * CP-058's frozen plan: the leg about to be driven, and nothing that identifies it.
     *
     * **The leg, not the route.** Where a charging stop was found the leg ends at the charger,
     * because arriving under the reserve at a destination the plan says to charge before is the
     * plan working, not the plan failing. A `Stop` with no charger found produces nothing at all:
     * that plan already says the trip does not work, and watching it drift adds nothing.
     *
     * The rate carries the climb, folded by the planner's own [ChargeStopPlan.effectiveRate], so
     * the comparison on the road is against the rate that actually planned rather than a second
     * arithmetic that would slowly disagree with it.
     *
     * The odometer is the adapter's, because `PERF_ODOMETER` answers nothing on this car
     * (CP-003). Without it there is no distance to measure a drive against, and the companion
     * says so rather than guessing from a trip that may not have been started.
     */
    private fun follow(
        plan: ChargeStopPlan.Plan,
        charger: Found?,
        route: OrsDirections.Route,
        rate: SocRate?,
        grade: RouteGrade.Cost?,
    ): FollowedPlan.Values? {
        val soc = snapshot?.socPercent?.toDouble() ?: return null
        val base = rate ?: return null
        val legKm = when (plan) {
            is ChargeStopPlan.Plan.NoStop -> route.distanceKm
            is ChargeStopPlan.Plan.Stop -> charger?.alongKm ?: return null
            is ChargeStopPlan.Plan.Refused -> return null
        }
        if (!legKm.isFinite() || legKm <= 0.0) return null
        val effective = ChargeStopPlan.effectiveRate(base, route.distanceKm, grade)
        SaicNav.connect(this)
        val odometer = runCatching { SaicNav.totalMileageKm() }.getOrNull()?.toDouble()
            ?: snapshot?.odometerKm?.toDouble()
            ?: return null
        val settings = VehicleSettings.read(this)
        return FollowedPlan.Values(
            committedAtMs = System.currentTimeMillis(),
            odometerAtDepartureKm = odometer,
            drift = PlanDrift.Followed(
                legKm = legKm,
                socAtDeparturePercent = soc,
                plannedRatePercentPerKm = effective.percentPerKm,
                plannedUncertaintyPercentPerKm = effective.uncertaintyPercentPerKm,
                reservePercent = settings.reservePercent,
            ),
            sections = route.sections,
            toStop = charger != null && plan is ChargeStopPlan.Plan.Stop,
        )
    }

    /**
     * Sends it. The first thing this application asks a vehicle system to do rather than tell it.
     *
     * Parked, on a tap, once. The gate is re-read here and not trusted from [render]: the button
     * was enabled at some earlier moment and the car may have moved since, and a navigation
     * screen changing under someone at 110 is the hazard this whole screen is arranged around.
     */
    private fun navigate() {
        val target = handoff ?: return
        if (ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, System.currentTimeMillis())
            != ParkedDeletionGate.PARKED
        ) {
            announce(getString(R.string.charge_stop_moving))
            return
        }
        val uri = NavigationHandoff.geoUri(target.latitude, target.longitude, target.label)
            ?: run {
                announce(getString(R.string.charge_stop_navigate_bad_place))
                return
            }
        // No coordinates in the probe line: this file leaves the car on a USB stick. The map
        // package is a package name, which identifies the car's software and not the driver.
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "driver tapped the handoff, to=${if (target.toStop) "charging stop" else "destination"}" +
                ", map=${MapApps.installedPackage(this) ?: "none installed"}"
        }
        // The map opens and the destination stays behind, on two drives now. The intent filters
        // say nothing because there are none; the exported activities are what is left to aim at,
        // so the next bundle carries their names instead of another round of guessing.
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            val entries = MapApps.entryPoints(this)
            if (entries.isEmpty()) "the map exports no activity this app is allowed to see"
            else "map entry points (${entries.size}): ${entries.joinToString(" | ")}"
        }
        // Written down on the tap and not on the render: this is the one unambiguous "I am
        // driving this" the app ever gets, and a plan merely looked at is not one being driven.
        // Recorded whether or not anything accepted the intent — the driver still chose it.
        followed?.let { FollowedPlan.write(this, it.copy(committedAtMs = System.currentTimeMillis())) }
            ?: FollowedPlan.clear(this)
        // Q10 otherwise says nothing until fifteen kilometres have been driven, and a drive cut
        // short then leaves it blank — indistinguishable from a companion that never armed.
        ValidationProbe.record(ValidationQuestion.PLAN_DRIFT) {
            followed?.let {
                "armed by the tap: leg=${String.format(Locale.ROOT, "%.0f", it.drift.legKm)} km, " +
                    "rate=${String.format(Locale.ROOT, "%.3f", it.drift.plannedRatePercentPerKm)} %/km, " +
                    "odometer=known, sections=${it.sections.size}"
            } ?: "not armed: no rate, no odometer, or a stop with no charger found"
        }
        announce(getString(R.string.charge_stop_navigate_sending))
        // Whatever the previous tap left running stops being the plan the moment this one starts.
        NavLegs.disarm()
        // Off the main thread, and not as a nicety: the adapter's transaction is not `oneway`
        // and it fans out to every registered listener while holding the lock on its callback
        // list, so this call waits for the navigation app to come back.
        worker.execute {
            // A route already running would read true whatever this tap does, so it is read
            // first: after that, `isMapNavigating` can no longer prove anything about us.
            val wasGuiding = SaicNavGuidance.isMapNavigating() == true
            // The route handoff first, since the drive of 2026-09-09 16:34 settled which channel
            // drives: `route taken=true guiding=true`, against `goTo taken=true guiding=false` on
            // the same tap and on the tap before it. Telenav takes transaction 23 and does nothing
            // with it. `goTo` stays below as the rung for a head unit that behaves otherwise —
            // it costs nothing on a tap that already worked, because it is not reached.
            val route = target.destination?.let {
                runCatching { SaicNavGuidance.startNavFromEvRoute(it, target.pathway) }
                    .getOrDefault(false)
            } ?: false
            // Raised before the wait, not after it: the adapter drives guidance on the binder
            // and leaves the screen on this app, so the destination went over and the driver
            // watched a page that says *Transmission…*. The map comes up now and the polling
            // below only settles what the message will say.
            if (route) MapApps.toForeground(this)
            val routeGuided = route && !wasGuiding && carStartedGuiding()
            val goTo = if (routeGuided) false else target.point?.let {
                runCatching { SaicNavGuidance.goTo(it) }.getOrDefault(false)
            } ?: false
            if (goTo && !route) MapApps.toForeground(this)
            val guided = goTo && !wasGuiding && carStartedGuiding()
            // Every rung in one line, so a drive that ends with nothing happening still says
            // which rung refused rather than leaving the next build to guess again.
            ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
                "channels: adapter=${SaicNavGuidance.isAvailable}, guiding before=$wasGuiding, " +
                    "goTo taken=$goTo guiding=$guided, route taken=$route guiding=$routeGuided"
            }
            // A plan with a charging stop is two legs, and the chain hands the second one over
            // when the first is done. Armed behind whichever channel the adapter took, `goTo` or
            // the route: the route carries a pathway, but nothing on this car has yet shown that
            // Telenav drives through it, and the chain is harmless if it does — guidance simply
            // stays on to the end and the chain finishes without sending anything.
            if (route || goTo) NavLegs.arm(target.legs) else NavLegs.disarm()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    guided || routeGuided -> guidanceRunning(target, viaRoute = routeGuided)
                    // Accepted, and the car cannot say what came of it because it was already
                    // guiding when the tap happened. Reported as asked, never as started.
                    wasGuiding && (route || goTo) -> guidanceAsked(target, viaGoTo = goTo)
                    route -> sentAsRoute(target)
                    else -> sendAsPoint(target, uri)
                }
            }
        }
    }

    /**
     * Waits for the navigation app to say it is guiding, and gives up saying nothing happened.
     *
     * The flag comes back through `MapService` from the navigation app itself, so this is the map
     * answering rather than this app hoping. The wait exists because a destination has to be
     * routed before guidance begins, and that takes seconds on a head unit; it runs on the worker
     * and the screen stays on *Transmission…* meanwhile.
     */
    private fun carStartedGuiding(): Boolean {
        repeat(GUIDANCE_POLLS) {
            Thread.sleep(GUIDANCE_POLL_MS)
            if (SaicNavGuidance.isMapNavigating() == true) return true
        }
        return false
    }

    /** The map says it is guiding, and it was not before the tap. The one unambiguous outcome. */
    private fun guidanceRunning(target: Handoff, viaRoute: Boolean) {
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "the car is guiding: channel=${if (viaRoute) "startNavFromEVRout" else "goTo"}" +
                ", to=${if (target.toStop) "charging stop" else "destination"}" +
                ", confirmed by isMapNavigating"
        }
        announce(
            getString(
                if (target.toStop) R.string.charge_stop_navigate_guiding_on_stop
                else R.string.charge_stop_navigate_guiding_on
            )
        )
    }

    /**
     * The adapter took the command while a route was already running, which is the one case the
     * car cannot answer: `isMapNavigating` was true before the tap and stays true after it,
     * whatever the navigation app did with the new destination. Said as asked, never as started.
     */
    private fun guidanceAsked(target: Handoff, viaGoTo: Boolean) {
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "adapter took ${if (viaGoTo) "goTo" else "startNavFromEVRout"}: " +
                "to=${if (target.toStop) "charging stop" else "destination"}; " +
                "already guiding before the tap, so the car cannot say what came of it"
        }
        announce(
            getString(
                if (target.toStop) R.string.charge_stop_navigate_guiding_stop
                else R.string.charge_stop_navigate_guiding
            )
        )
    }

    /**
     * The route went over and the car is not guiding. That is what the drive of 2026-09-09 saw:
     * MG4 Navigator showed the destination and stayed put, and the flag now says as much instead
     * of leaving it to the driver to notice. The map has the route; starting it is a tap on the
     * map, and the screen says so rather than pretending the trip is under way.
     */
    private fun sentAsRoute(target: Handoff) {
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "adapter took the route: pathway=${target.pathway.size} point(s), destination=1; " +
                "goTo did not start guidance and neither did this — isMapNavigating stayed false"
        }
        announce(
            getString(
                if (target.pathway.isEmpty()) R.string.charge_stop_navigate_route
                else R.string.charge_stop_navigate_route_via
            )
        )
    }

    /** The adapter refused or is not there: the platform's own channel, one point, as before. */
    private fun sendAsPoint(target: Handoff, uri: String) {
        val outcome = MapApps.open(this, uri, target.latitude, target.longitude)
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            when (outcome) {
                MapApps.Outcome.SENT -> "accepted by something, with the destination"
                MapApps.Outcome.MAP_ONLY ->
                    "map opened by component; whether it read the destination is what the " +
                        "driver has to say, because nothing on this car reports it back"
                MapApps.Outcome.NONE -> "nothing accepted geo: and no map component is installed"
            }
        }
        announce(
            when (outcome) {
                MapApps.Outcome.SENT -> getString(R.string.charge_stop_navigate_sent)
                // The place the driver now has to type, in the one notation a search field
                // takes: a French head unit would render 43,5 and the comma is the separator.
                MapApps.Outcome.MAP_ONLY -> getString(
                    R.string.charge_stop_navigate_map_only,
                    String.format(Locale.ROOT, "%.5f, %.5f", target.latitude, target.longitude),
                )
                MapApps.Outcome.NONE -> getString(R.string.charge_stop_navigate_none)
            }
        )
    }

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
        binding.chooseDestinationAction.isEnabled = usable
        // Disabled, never hidden: the action stays where the driver last saw it, and a button
        // that is on screen and grey says "not yet" where an absent one says "this screen has
        // no way out of it".
        binding.navigateAction.isEnabled = usable && handoff != null

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

    /** Probe text is read at a desk, not by a driver: point decimals, whatever the locale. */
    private fun probeFormat(value: Double, pattern: String): String =
        String.format(Locale.ROOT, pattern, value)

    private fun format(value: Double, pattern: String): String =
        String.format(Locale.getDefault(), pattern, value)

    /** A charger, where it falls on this route, and the charge left on reaching it. */
    /** CP-057's third row: another road, planned, with no charger search spent on it. */
    private data class MotorwayFree(
        val route: OrsDirections.Route,
        val plan: ChargeStopPlan.Plan,
    )

    /**
     * What the button would send, held between the render that offered it and the tap.
     *
     * Three shapes of the same plan, because the car has three channels and each takes a
     * different amount of it. [latitude], [longitude] and [label] are the single point a `geo:`
     * URI can carry — the charging stop when there is one, since that is the leg the forecast is
     * about. [point] is that same leg target validated for the adapter's `goTo`, the command that
     * starts guidance. [destination] and [pathway] are the whole plan, for the route handoff,
     * which has a waypoint list and therefore needs no such choice — and which, on the car, drew
     * the route without ever driving it.
     */
    private data class Handoff(
        val latitude: Double,
        val longitude: Double,
        val label: String?,
        val toStop: Boolean,
        val point: NavigationHandoff.Poi?,
        val destination: NavigationHandoff.Poi?,
        val pathway: List<NavigationHandoff.Poi>,
        /**
         * The trip in order, first leg first, for the chain that drives it one point at a time.
         * The leg the tap sends is in it: the chain counts from the leg already handed over.
         */
        val legs: List<NavigationHandoff.Poi>,
    )

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

        /** Enough sections for a validation line to show the shape of a route, not all of it. */
        const val VALIDATION_SECTIONS = 8

        /**
         * How long to let the navigation app route a destination before calling a channel dead.
         *
         * Four seconds was measured against a road the head unit already had offline, and the
         * bundle of 2026-09-10 caught it short: the route was taken at thirty-four seconds and
         * `isMapNavigating` only turned true at forty, so a channel that worked was recorded as
         * refused and the second one was tried on top of it. Eight covers the six that were
         * seen, and the wait is a worker thread — the screen is not blocked on it.
         */
        const val GUIDANCE_POLLS = 16
        const val GUIDANCE_POLL_MS = 500L
    }
}
