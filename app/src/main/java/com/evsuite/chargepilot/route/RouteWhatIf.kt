package com.evsuite.chargepilot.route

import com.evsuite.chargepilot.SpeedWhatIfUnavailable
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.RouteGrade
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.model.EnergyModel

/**
 * What slowing down, or going the other way, would cost and save on the road ahead.
 *
 * This is the sentence the project was described by — *"si tu ralentis à 100, pas de recharge
 * nécessaire mais un retard de X minutes"* — and the two halves of it are inseparable. A charge
 * saving shown without the delay it costs is a recommendation wearing a fact's clothes, so
 * every answer here carries both numbers or is not produced at all.
 *
 * **Where slowing down is a choice comes from the route, not from a dataset.** A section's
 * implied speed is the router's own duration over its own distance; a road the router expects
 * to be driven at 80 is not a road where 100 is available, and no speed-limit source is
 * consulted to know that. It also means an urban section, a hill and a bad junction all look
 * like what they are — slow — without anything having to model them.
 *
 * Consumption comes from CP-032's fitted model and nowhere else. Outside its trained envelope
 * this refuses, exactly as the post-trip comparison does: the whole point of an envelope is
 * that the fit says nothing beyond it, and a number produced there would be invention.
 */
object RouteWhatIf {

    /** Fastest first, because the useful answer is the mildest change that works. */
    val SPEEDS_KMH = listOf(130, 120, 110, 100, 90)

    /** Below this the road is not one where a driver chooses the speed. */
    const val MIN_SECTION_SPEED_KMH = 95.0

    /** Less road than this and the saving is noise dressed as a decision. */
    const val MIN_AFFECTED_KM = 5.0

    /**
     * One speed, both consequences.
     *
     * [savedPercentLow] can be negative where the bands overlap, and is left that way: "between
     * -1 and +6 %" is the honest reading of a model that does not separate the two speeds, and
     * clamping it at zero would turn an uncertainty into a promise.
     */
    data class Slower(
        val speedKmh: Int,
        val affectedKm: Double,
        val delayMinutes: Double,
        val savedPercentLow: Double,
        val savedPercentHigh: Double,
        val removesStop: Boolean,
    )

    /** Another road, against the planned one. Both deltas, same rule. */
    data class Alternative(
        val viaLabel: String?,
        val distanceDeltaKm: Double,
        val delayMinutes: Double,
        val savedPercentLow: Double,
        val savedPercentHigh: Double,
    )

    sealed interface Result {
        /**
         * @param headline the mildest slowdown that removes the charging stop, or null when
         *   none does. Deliberately the fastest such speed and not the slowest: every speed
         *   below it also removes the stop, so the one worth putting first is the one that
         *   costs the driver least time.
         */
        data class Ready(val options: List<Slower>, val headline: Slower?) : Result

        data class Unavailable(val reason: SpeedWhatIfUnavailable) : Result
    }

    /**
     * @param sections the road ahead, from [OrsDirections.Route.sections].
     * @param model CP-032's fit, or null when this firmware never trained one.
     * @param outsideTempCelsius the vehicle's own reading; the fit is a function of it.
     * @param pack what the energy is a percentage of.
     * @param removesStop asked with the pessimistic edge of a saving: would the plan still
     *   need a stop if the driver gained this much charge. The planner answers it, so this
     *   file never learns what a reserve is.
     */
    fun slower(
        sections: List<OrsDirections.Section>,
        model: EnergyModel?,
        outsideTempCelsius: Double?,
        pack: BatteryCapacityConfig,
        removesStop: (Double) -> Boolean = { false },
    ): Result {
        if (model == null) {
            return Result.Unavailable(SpeedWhatIfUnavailable.MODEL_NOT_TRAINED)
        }
        if (outsideTempCelsius == null || !outsideTempCelsius.isFinite()) {
            return Result.Unavailable(SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE)
        }
        val fast = sections.filter { (it.impliedSpeedKmh ?: 0.0) >= MIN_SECTION_SPEED_KMH }
        if (fast.sumOf { it.distanceKm } < MIN_AFFECTED_KM) {
            return Result.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
        }

        val attempts = SPEEDS_KMH.map { speed ->
            at(speed, fast, model, outsideTempCelsius, pack, removesStop)
        }
        // A speed with no road to apply it to is not an option: "130 km/h · 0 min · 0 %" is a
        // row that says nothing, on a screen where every row has to earn its line.
        val options = attempts.filterIsInstance<Attempt.Ok>().map { it.slower }
        if (options.isEmpty()) {
            return Result.Unavailable(
                if (attempts.any { it is Attempt.OutsideEnvelope }) {
                    SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE
                } else {
                    SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION
                }
            )
        }
        return Result.Ready(options, options.firstOrNull { it.removesStop })
    }

    /** Why a candidate speed produced nothing, kept apart because the reasons read differently. */
    private sealed interface Attempt {
        data class Ok(val slower: Slower) : Attempt

        /** The fit says nothing at this speed or this temperature, so neither does this. */
        object OutsideEnvelope : Attempt

        /** Nothing on this route is being driven faster than the candidate. */
        object NoRoad : Attempt
    }

    private fun at(
        speedKmh: Int,
        sections: List<OrsDirections.Section>,
        model: EnergyModel,
        outsideTempCelsius: Double,
        pack: BatteryCapacityConfig,
        removesStop: (Double) -> Boolean,
    ): Attempt {
        var affectedKm = 0.0
        var delayMinutes = 0.0
        var savedLowKwh = 0.0
        var savedHighKwh = 0.0
        for (section in sections) {
            val implied = section.impliedSpeedKmh ?: continue
            // Nothing to choose: this road is already being driven at or below the candidate.
            if (implied <= speedKmh) continue
            val now = model.predict(implied, outsideTempCelsius)
            val slowed = model.predict(speedKmh.toDouble(), outsideTempCelsius)
            val nowValue = now.value ?: return Attempt.OutsideEnvelope
            val slowedValue = slowed.value ?: return Attempt.OutsideEnvelope
            val nowBand = now.uncertainty ?: 0.0
            val slowedBand = slowed.uncertainty ?: 0.0

            // The two predictions come from one fit and share its residual, so treating their
            // errors as independent widens the band further than the arithmetic requires. That
            // is the safe direction — a saving that looks smaller than it is — and the honest
            // alternative would need a covariance this model does not carry.
            val per100 = section.distanceKm / 100.0
            savedLowKwh += ((nowValue - nowBand) - (slowedValue + slowedBand)) * per100
            savedHighKwh += ((nowValue + nowBand) - (slowedValue - slowedBand)) * per100
            delayMinutes += section.distanceKm / speedKmh * 60.0 - section.durationMinutes
            affectedKm += section.distanceKm
        }
        if (affectedKm < MIN_AFFECTED_KM) return Attempt.NoRoad

        val low = pack.socPercentForEnergy(savedLowKwh).value ?: return Attempt.OutsideEnvelope
        val high = pack.socPercentForEnergy(savedHighKwh).value ?: return Attempt.OutsideEnvelope
        return Attempt.Ok(
            Slower(
                speedKmh = speedKmh,
                affectedKm = affectedKm,
                delayMinutes = delayMinutes,
                savedPercentLow = low,
                savedPercentHigh = high,
                removesStop = removesStop(low),
            )
        )
    }

    /**
     * The other road, against the planned one.
     *
     * The charge difference is the route's own arithmetic — distance at the driver's rate, plus
     * what the climb costs — rather than a second model, because the two routes are driven by
     * the same car on the same day and only the road changed.
     */
    fun alternative(
        planned: OrsDirections.Route,
        other: OrsDirections.Route,
        rate: SocRate?,
        pack: BatteryCapacityConfig,
    ): Alternative? {
        if (rate == null || rate.percentPerKm <= 0.0) return null
        if (other.distanceKm <= 0.0 || !other.distanceKm.isFinite()) return null

        val plannedCost = costPercent(planned, rate, pack)
        val otherCost = costPercent(other, rate, pack)
        val saved = plannedCost.first - otherCost.first
        val band = plannedCost.second + otherCost.second
        return Alternative(
            viaLabel = other.viaLabel,
            distanceDeltaKm = other.distanceKm - planned.distanceKm,
            delayMinutes = other.durationMinutes - planned.durationMinutes,
            savedPercentLow = saved - band,
            savedPercentHigh = saved + band,
        )
    }

    /** What a route costs in charge, and how wide that is. */
    private fun costPercent(
        route: OrsDirections.Route,
        rate: SocRate,
        pack: BatteryCapacityConfig,
    ): Pair<Double, Double> {
        val grade = RouteGrade.of(route.ascentMetres, route.descentMetres, pack)
        return Pair(
            rate.percentPerKm * route.distanceKm + (grade?.percent ?: 0.0),
            rate.uncertaintyPercentPerKm * route.distanceKm + (grade?.uncertaintyPercent ?: 0.0),
        )
    }
}
