package com.evsuite.chargepilot

import com.evsuite.chargepilot.route.OpenChargeMap
import com.evsuite.chargepilot.route.OrsGeocode
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
 * which has a waypoint list and therefore needs no such choice — and which, on the car, drew
 * the route without ever driving it.
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
)

internal object ChargeStopHandoff {

    /**
     * The handoff for a planned trip.
     *
     * @param stop the charger this plan stops at, or null for a trip that needs none — and also
     *   for a `Stop` plan that found no charger, which has no coordinates to send.
     *
     * A point that fails [NavigationHandoff.poi]'s validation drops out of the lists rather than
     * out of the plan: the `geo:` fallback still has somewhere to go, because [latitude] and
     * [longitude] are carried as numbers and not as a validated POI.
     */
    fun of(place: OrsGeocode.Place, stop: Found?): Handoff {
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
        return if (stop != null) {
            Handoff(
                stop.charger.latitude, stop.charger.longitude, stop.charger.name, toStop = true,
                point = pathway.firstOrNull(), destination = destination, pathway = pathway,
                legs = pathway + listOfNotNull(destination),
            )
        } else {
            Handoff(
                place.latitude, place.longitude, place.label, toStop = false,
                point = destination, destination = destination, pathway = pathway,
                legs = listOfNotNull(destination),
            )
        }
    }

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
