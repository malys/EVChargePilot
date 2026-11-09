package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.chargepilot.route.OrsDirections
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.telemetry.PlanDrift
import java.util.Locale

/**
 * The plan the driver actually set off on, written down at the moment they set off on it.
 *
 * CP-058 needs to compare a drive against a plan, and by the time the drive is happening the
 * screen that made the plan is gone, the process may have been restarted and the route was two
 * network requests ago. So the chosen plan is frozen on the tap that hands it to the car — the
 * one unambiguous "I am driving this" in the whole app — and everything after that is arithmetic
 * on numbers already in this file.
 *
 * **Only the leg being driven.** Where the plan has a charging stop, the leg ends at the charger,
 * because arriving under the reserve at a destination the plan says to charge before is not
 * drift, it is the plan working.
 *
 * **What is deliberately not stored.** No destination, no coordinate, no place name, no road
 * name. None of it is needed — the drift arithmetic wants distances and the what-if wants
 * durations — and all of it would be a trip's itinerary sitting in a preferences file that a
 * diagnostic bundle walks past. What remains is a distance, a charge, two rates and a list of
 * number pairs, which describes no particular journey.
 *
 * Ordinary app-private preferences and a compact text encoding rather than JSON: the sections are
 * two doubles each, a reflective serialiser would need a `-keep` rule to survive R8, and this
 * needs neither.
 */
object FollowedPlan {

    /**
     * After this the plan is forgotten on its own.
     *
     * A plan is about a drive, and a drive that has not finished within half a day did not
     * happen. Without an expiry the alternative is a companion still commenting on last week's
     * trip to Alès, which is worse than saying nothing.
     */
    const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /**
     * More sections than this and none are kept.
     *
     * A route is a few hundred manoeuvres; a pathological one is not worth a preferences file
     * measured in tens of kilobytes. Dropping all of them costs the speed-to-restore row and
     * says so, which is the honest failure — a truncated list would silently move the road.
     */
    const val MAX_SECTIONS = 800

    /**
     * @param sections the road ahead as it was at departure, for CP-049's what-if. Empty when the
     *   route had too many to keep.
     * @param toStop whether the leg ends at a charging stop rather than at the destination.
     * @param shaped whether the handoff carried shape points, so the car's remaining distance is
     *   to the next of them rather than to the end of the leg.
     */
    data class Values(
        val committedAtMs: Long,
        val odometerAtDepartureKm: Double,
        val drift: PlanDrift.Followed,
        val sections: List<OrsDirections.Section>,
        val toStop: Boolean,
        val shaped: Boolean = false,
    )

    fun write(context: Context, values: Values) {
        prefs(context).edit()
            .putLong(KEY_COMMITTED_AT, values.committedAtMs)
            .putFloat(KEY_ODOMETER, values.odometerAtDepartureKm.toFloat())
            .putFloat(KEY_LEG_KM, values.drift.legKm.toFloat())
            .putFloat(KEY_SOC_AT_DEPARTURE, values.drift.socAtDeparturePercent.toFloat())
            .putFloat(KEY_RATE, values.drift.plannedRatePercentPerKm.toFloat())
            .putFloat(KEY_RATE_BAND, values.drift.plannedUncertaintyPercentPerKm.toFloat())
            .putFloat(KEY_RESERVE, values.drift.reservePercent.toFloat())
            .putBoolean(KEY_TO_STOP, values.toStop)
            .putBoolean(KEY_SHAPED, values.shaped)
            .putString(KEY_SECTIONS, encode(values.sections))
            .apply()
    }

    /** The plan, or null when there is none, it expired, or the file is not one this can read. */
    fun read(context: Context, nowMs: Long = System.currentTimeMillis()): Values? {
        val prefs = prefs(context)
        val committedAt = prefs.getLong(KEY_COMMITTED_AT, 0L)
        if (committedAt <= 0L) return null
        // A clock that moved backwards is as good a reason to forget as an old plan: nothing
        // downstream can tell how far into a drive it is if the departure is in the future.
        if (nowMs - committedAt !in 0..MAX_AGE_MS) return null
        val legKm = prefs.getFloat(KEY_LEG_KM, Float.NaN).toDouble()
        val rate = prefs.getFloat(KEY_RATE, Float.NaN).toDouble()
        val soc = prefs.getFloat(KEY_SOC_AT_DEPARTURE, Float.NaN).toDouble()
        val odometer = prefs.getFloat(KEY_ODOMETER, Float.NaN).toDouble()
        if (!legKm.isFinite() || legKm <= 0.0) return null
        if (!rate.isFinite() || rate <= 0.0) return null
        if (!soc.isFinite() || !odometer.isFinite()) return null
        return Values(
            committedAtMs = committedAt,
            odometerAtDepartureKm = odometer,
            drift = PlanDrift.Followed(
                legKm = legKm,
                socAtDeparturePercent = soc,
                plannedRatePercentPerKm = rate,
                plannedUncertaintyPercentPerKm =
                    prefs.getFloat(KEY_RATE_BAND, 0f).toDouble().takeIf { it.isFinite() } ?: 0.0,
                reservePercent = prefs.getFloat(KEY_RESERVE, 0f).toDouble()
                    .takeIf { it.isFinite() } ?: VehicleSettings.DEFAULT_RESERVE_PERCENT,
            ),
            sections = decode(prefs.getString(KEY_SECTIONS, null)),
            toStop = prefs.getBoolean(KEY_TO_STOP, false),
            shaped = prefs.getBoolean(KEY_SHAPED, false),
        )
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /**
     * What is left of the leg, when the car's own figure is only to a shape point.
     *
     * Telenav counts to its next waypoint, and a shaped handoff puts points along the road before
     * the leg's end. A car figure short of the leg's arithmetic is that point; one at or past it
     * is the leg's end itself or a guidance to somewhere else, and both are the car's to give.
     * Null means: use the car's figure.
     */
    internal fun legLeftKm(plan: Values?, carKm: Double?, odometerKm: Double?): Double? {
        if (plan == null || !plan.shaped || carKm == null || odometerKm == null) return null
        val left = (plan.drift.legKm - (odometerKm - plan.odometerAtDepartureKm)).coerceAtLeast(0.0)
        return left.takeIf { carKm < it }
    }

    /** [legLeftKm] for the plan on file, on the adapter's odometer. Call off the main thread. */
    fun legLeftKm(context: Context, carKm: Double?): Double? {
        if (carKm == null) return null
        val plan = read(context)?.takeIf { it.shaped } ?: return null
        SaicNav.connect(context)
        val odometer = runCatching { SaicNav.totalMileageKm() }.getOrNull()?.toDouble()
        return legLeftKm(plan, carKm, odometer)
    }

    /**
     * The road still in front of the car, after [drivenKm] of the stored route.
     *
     * The section the car is inside is kept, scaled: distance and duration are cut by the same
     * fraction, so its implied speed — the only thing CP-049 reads from it — is unchanged. The
     * alternative, dropping it whole, would delete up to a whole motorway leg from the answer.
     */
    fun ahead(sections: List<OrsDirections.Section>, drivenKm: Double): List<OrsDirections.Section> {
        if (drivenKm <= 0.0 || !drivenKm.isFinite()) return sections
        var passed = 0.0
        val rest = ArrayList<OrsDirections.Section>(sections.size)
        for (section in sections) {
            val end = passed + section.distanceKm
            when {
                end <= drivenKm -> Unit
                passed >= drivenKm -> rest.add(section)
                else -> {
                    val fraction = (end - drivenKm) / section.distanceKm
                    rest.add(
                        section.copy(
                            distanceKm = section.distanceKm * fraction,
                            durationMinutes = section.durationMinutes * fraction,
                        )
                    )
                }
            }
            passed = end
        }
        return rest
    }

    /** `distanceKm:durationMinutes` pairs, semicolon separated. Road names are not kept. */
    internal fun encode(sections: List<OrsDirections.Section>): String {
        if (sections.isEmpty() || sections.size > MAX_SECTIONS) return ""
        return sections.joinToString(";") {
            String.format(Locale.ROOT, "%.4f:%.4f", it.distanceKm, it.durationMinutes)
        }
    }

    /** A malformed pair drops the whole list: half a route is a road that does not exist. */
    internal fun decode(text: String?): List<OrsDirections.Section> {
        if (text.isNullOrEmpty()) return emptyList()
        val parts = text.split(';')
        if (parts.size > MAX_SECTIONS) return emptyList()
        val sections = ArrayList<OrsDirections.Section>(parts.size)
        for (part in parts) {
            val split = part.indexOf(':')
            if (split <= 0) return emptyList()
            val distance = part.substring(0, split).toDoubleOrNull() ?: return emptyList()
            val duration = part.substring(split + 1).toDoubleOrNull() ?: return emptyList()
            if (!distance.isFinite() || !duration.isFinite()) return emptyList()
            if (distance < 0.0 || duration < 0.0) return emptyList()
            sections.add(OrsDirections.Section(distance, duration, null))
        }
        return sections
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private const val FILE_NAME = "chargepilot_followed_plan"
    private const val KEY_COMMITTED_AT = "committed_at_ms"
    private const val KEY_ODOMETER = "odometer_km"
    private const val KEY_LEG_KM = "leg_km"
    private const val KEY_SOC_AT_DEPARTURE = "soc_at_departure_percent"
    private const val KEY_RATE = "rate_percent_per_km"
    private const val KEY_RATE_BAND = "rate_band_percent_per_km"
    private const val KEY_RESERVE = "reserve_percent"
    private const val KEY_TO_STOP = "to_stop"
    private const val KEY_SHAPED = "shaped"
    private const val KEY_SECTIONS = "sections"
}
