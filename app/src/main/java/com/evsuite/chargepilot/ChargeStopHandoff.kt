package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.chargepilot.route.OpenChargeMap
import com.evsuite.chargepilot.route.OrsDirections
import com.evsuite.chargepilot.route.OrsGeocode
import com.evsuite.chargepilot.route.RouteGeometry
import com.evsuite.hardware.saic.NavigationHandoff
import com.evsuite.hardware.telemetry.ChargeStopPlan
import com.evsuite.hardware.telemetry.RouteGrade
import com.evsuite.hardware.telemetry.SocRate

/**
 * What the "Y aller" button would send, and which charger it is about.
 *
 * Lifted out of `ChargeStopActivity` because none of it needs an `Activity`: it is three
 * coordinates and an order, and the order is the part that matters. Sending the destination where
 * the stop was meant pins the wrong leg, and every figure on the screen quietly becomes a figure
 * about a different trip — a mistake that costs nothing to make and is invisible until someone
 * arrives under the reserve. Here it is one function with a test around it.
 */

/**
 * A charger the plan actually stops at, and where on the route it falls.
 *
 * @param alongKm distance from the origin, which is also the leg the drift companion watches.
 * @param arrivalPercent charge on reaching it, or null when there is no rate to compute one.
 */
internal data class Found(
    val charger: OpenChargeMap.Charger,
    val alongKm: Double,
    val arrivalPercent: Double?,
)

/**
 * What the button would send, held between the render that offered it and the tap.
 *
 * Three shapes of the same plan, because the car has three channels and each takes a
 * different amount of it. [latitude], [longitude] and [label] are the single point a `geo:`
 * URI can carry — the charging stop when there is one, since that is the leg the forecast is
 * about. [point] is that same leg target validated for the adapter's `goTo`, the command that
 * starts guidance. [destination] and [pathway] are the whole plan, for the route handoff,
 * which has a waypoint list and therefore needs no such choice — the charging stops, and points
 * along the road between them, so the car drives the route the screen costed rather than one of
 * its own choosing.
 *
 * **Shape points, spaced out.** CP-060 sent eight of them whatever the trip's length; on the
 * 7 km drive of 2026-10-19 that was one every 900 m, Telenav guided to the next one ("900 m"),
 * and guidance dropped about 2 km in. They were removed, then restored on the owner's request
 * with [ChargeStopHandoff.MIN_SHAPE_SPACING_KM] between them: a short trip gets none, exactly as
 * in the build that removed them. Telenav's remaining distance is still to the next waypoint, so
 * [shapePoints] tells the companion to count from the planned road instead. A setting,
 * [ChargeStopHandoff.shapePointsEnabled], turns them off; on by default.
 */
internal data class Handoff(
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
    /** Points of the road's shape in [pathway]: places the car passes, never legs. */
    val shapePoints: Int = 0,
    /** How far along the planned road the first charging stop sits, when there is one. */
    val firstStopKm: Double? = null,
)

internal object ChargeStopHandoff {

    /**
     * The handoff for a planned trip.
     *
     * @param stops the chargers this plan stops at, in the order they are driven — empty for a
     *   trip that needs none, and also for a `Stop` plan that found no charger, which has no
     *   coordinates to send. A long route has more than one of them (CP-062), and all of them are
     *   carried: a trip handed over with only its first stop is a trip the car re-plans at the
     *   second one.
     *
     * A point that fails [NavigationHandoff.poi]'s validation drops out of the lists rather than
     * out of the plan: the `geo:` fallback still has somewhere to go, because [latitude] and
     * [longitude] are carried as numbers and not as a validated POI.
     */
    fun of(
        place: OrsGeocode.Place,
        stops: List<Found>,
        route: OrsDirections.Route? = null,
    ): Handoff {
        val destination = NavigationHandoff.poi(place.latitude, place.longitude, place.label)
        val stopsOnTheWay = stops.mapNotNull { stop ->
            NavigationHandoff.poi(stop.charger.latitude, stop.charger.longitude, stop.charger.name)
                ?.let { stop.alongKm to it }
        }
        // Ordered by distance along the road, because a pathway is an itinerary and not a set: a
        // waypoint after the destination in the list is a route that doubles back.
        // The stops are the points the plan is about and they keep their place in the list
        // whatever the shape costs: the budget is what is left over, not the other way round.
        val shape = shape(route, NavigationHandoff.MAX_PATHWAY_POINTS - stopsOnTheWay.size)
        val pathway = NavigationHandoff.pathway(
            (shape + stopsOnTheWay).sortedBy { it.first }.map { it.second }
        )
        // The stops first and the destination after them: that is the order they are driven in,
        // and the order the chain hands them over in once each leg's guidance ends. Shape points
        // are deliberately not legs — they are places the car passes, and a chain that treated one
        // as an arrival would re-send guidance in the middle of a motorway.
        val first = stops.firstOrNull()
        return if (first != null) {
            Handoff(
                first.charger.latitude, first.charger.longitude, first.charger.name, toStop = true,
                point = stopsOnTheWay.firstOrNull()?.second, destination = destination,
                pathway = pathway,
                legs = stopsOnTheWay.map { it.second } + listOfNotNull(destination),
                shapePoints = shape.size, firstStopKm = first.alongKm,
            )
        } else {
            Handoff(
                place.latitude, place.longitude, place.label, toStop = false,
                point = destination, destination = destination, pathway = pathway,
                legs = listOfNotNull(destination), shapePoints = shape.size,
            )
        }
    }

    /**
     * The road's shape, as the handful of points the adapter's pathway can carry.
     *
     * Why this exists: the handoff names where the driver is going, and the car plans its own way
     * there. Every figure on the screen — the arrival charge, the stop, the drift the companion
     * watches — is about *this* route, so a car that takes another road quietly makes all of them
     * about a different trip. Points along the way are the only instruction available; they do
     * not force the road, they constrain it.
     *
     * At most one per [MIN_SHAPE_SPACING_KM] of road, so a trip shorter than twice that gets none.
     *
     * Named by the road they sit on where the router said what it is. A coordinate is what falls
     * out otherwise, and a map showing `43.612345,3.876543` as a waypoint label is honest about
     * being a point on a line.
     */
    private fun shape(
        route: OrsDirections.Route?,
        budget: Int,
    ): List<Pair<Double, NavigationHandoff.Poi>> {
        if (route == null) return emptyList()
        val count = minOf(budget, (route.distanceKm / MIN_SHAPE_SPACING_KM).toInt() - 1)
        if (count < 1) return emptyList()
        return RouteGeometry.evenlySpaced(route.points, count).mapNotNull { along ->
            NavigationHandoff.poi(
                along.point.latitude,
                along.point.longitude,
                roadAtKm(route.sections, along.alongKm),
            )?.let { along.alongKm to it }
        }
    }

    /** The road the router named for this distance along the route, when it named one. */
    private fun roadAtKm(sections: List<OrsDirections.Section>, km: Double): String? {
        var along = 0.0
        for (section in sections) {
            along += section.distanceKm
            if (km <= along) return section.road?.takeIf { it.isNotBlank() }
        }
        return null
    }

    /**
     * The least road between two shape points. Telenav guides to the next waypoint, and on
     * 2026-10-19 eight of them on 7 km made the guidance a series of 900 m hops: spaced this far
     * apart, a point is somewhere on a long road the car would otherwise re-plan, never the next
     * turning.
     */
    internal const val MIN_SHAPE_SPACING_KM = 40.0

    /** Whether the route handoff carries shape points. The driver's setting; on by default. */
    fun shapePointsEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_SHAPE, true)

    fun storeShapePointsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(PREF_SHAPE, enabled).apply()

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val PREFERENCES = "navigation_handoff"
    private const val PREF_SHAPE = "shape_points"

    /**
     * The handoff for a destination nothing has been planned to yet — no route, or a route still
     * being fetched. One leg, no pathway, and [Handoff.toStop] false: there is no stop to claim.
     */
    fun unplanned(place: OrsGeocode.Place): Handoff {
        val destination = NavigationHandoff.poi(place.latitude, place.longitude, place.label)
        return Handoff(
            place.latitude, place.longitude, place.label, toStop = false,
            point = destination, destination = destination, pathway = emptyList(),
            legs = listOfNotNull(destination),
        )
    }

    /**
     * Whether `goTo` goes out after the route handoff, the rung for a head unit whose map takes
     * the route and does nothing with it.
     *
     * Never when the route started the guidance, and never when the car was already guiding and
     * took the route: it cannot say what came of it, and a second destination on top of the one
     * it just took is the 2026-10-22 drive, where the map came forward over the split screen
     * before its pane had a surface.
     */
    fun sendsGoTo(routeTaken: Boolean, wasGuiding: Boolean, routeGuided: Boolean): Boolean =
        !routeTaken || (!wasGuiding && !routeGuided)

    /**
     * Would this much charge, freed by slowing down, remove the stop.
     *
     * The planner answers it rather than the what-if: the reserve, the band and the refusal
     * rules live in one place, and a second implementation of them would drift.
     */
    fun removesStop(
        socPercent: Double?,
        savedPercent: Double,
        routeKm: Double,
        rate: SocRate?,
        reservePercent: Double,
        grade: RouteGrade.Cost?,
    ): Boolean {
        if (socPercent == null) return false
        val freed = (socPercent + savedPercent).coerceAtMost(100.0)
        return ChargeStopPlan.of(freed, routeKm, rate, reservePercent, grade) is
            ChargeStopPlan.Plan.NoStop
    }
}
