package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.ChargeStopPlan

/**
 * The six numbers about *this* car and *this* driver that the app used to answer for them.
 *
 * A pack capacity from a specification sheet, a health of 100 % because nothing measured it, a
 * charger power floor and a reserve were constants in `ChargeStopActivity` — each with a
 * comment saying it should not be. A five-year-old pack is not a new one, a Standard Range is
 * not a Long Range, and a reserve is a risk appetite that belongs to whoever is taking the
 * risk.
 *
 * The defaults are still the MG4 Long Range specification. What changes is that they are
 * declared as assumptions on a screen the driver can correct, instead of being invisible.
 *
 * Ordinary app-private preferences, not the encrypted file the routing key lives in: these are
 * settings, not secrets, and `RoutingCredentials`' keystore fallback exists for a threat this
 * does not have.
 */
object VehicleSettings {

    /**
     * @param usableCapacityKwhWhenNew what the pack held new, not what it holds now.
     * @param stateOfHealthPercent what the driver believes is left of it.
     * @param minChargerPowerKw below this a mid-route stop is an overnight.
     * @param reservePercent charge the plan refuses to spend.
     * @param departurePercent charge a planned stop is left with, for a trip that needs more
     *   than one of them. How long the driver plugs in is theirs; this is where they say it.
     * @param referenceConsumptionKwhPer100Km the consumption the trip screen measures a drive
     *   against, as Tesla's "nominal" line: above it the pack empties faster than rated.
     */
    data class Values(
        val usableCapacityKwhWhenNew: Double = DEFAULT_CAPACITY_KWH,
        val stateOfHealthPercent: Double = DEFAULT_HEALTH_PERCENT,
        val minChargerPowerKw: Double = DEFAULT_MIN_POWER_KW,
        val reservePercent: Double = DEFAULT_RESERVE_PERCENT,
        val departurePercent: Double = DEFAULT_DEPARTURE_PERCENT,
        val referenceConsumptionKwhPer100Km: Double = DEFAULT_REFERENCE_CONSUMPTION,
    ) {
        val pack: BatteryCapacityConfig
            get() = BatteryCapacityConfig(usableCapacityKwhWhenNew, stateOfHealthPercent)

        /** True while every figure is still the specification sheet's rather than the driver's. */
        val isDefault: Boolean get() = this == Values()
    }

    /** Which field a driver has to fix, so the screen can say so instead of failing silently. */
    enum class Field { CAPACITY, HEALTH, MIN_POWER, RESERVE, DEPARTURE, REFERENCE }

    sealed interface Parsed {
        data class Ok(val values: Values) : Parsed

        data class Refused(val field: Field) : Parsed
    }

    /**
     * The typed text, or the field that is nonsense.
     *
     * Bounds rather than free numbers: a capacity of 0 divides the climb by nothing, a health of
     * 300 % invents a pack, and a reserve of 90 % turns every trip into a charging stop. An
     * empty field means the documented default, which is what a driver who cleared a box meant.
     */
    fun parse(
        capacity: String,
        health: String,
        minPower: String,
        reserve: String,
        departure: String,
        reference: String = "",
    ): Parsed {
        val capacityKwh = number(capacity, DEFAULT_CAPACITY_KWH)
            ?.takeIf { it in CAPACITY_RANGE } ?: return Parsed.Refused(Field.CAPACITY)
        val healthPercent = number(health, DEFAULT_HEALTH_PERCENT)
            ?.takeIf { it in HEALTH_RANGE } ?: return Parsed.Refused(Field.HEALTH)
        val powerKw = number(minPower, DEFAULT_MIN_POWER_KW)
            ?.takeIf { it in MIN_POWER_RANGE } ?: return Parsed.Refused(Field.MIN_POWER)
        val reservePercent = number(reserve, DEFAULT_RESERVE_PERCENT)
            ?.takeIf { it in RESERVE_RANGE } ?: return Parsed.Refused(Field.RESERVE)
        // Above the reserve, not merely inside its own range: a stop left with less charge than
        // the plan refuses to spend buys no kilometres, and the chain would say so leg after leg.
        val departurePercent = number(departure, DEFAULT_DEPARTURE_PERCENT)
            ?.takeIf { it in DEPARTURE_RANGE && it > reservePercent }
            ?: return Parsed.Refused(Field.DEPARTURE)
        val referenceConsumption = number(reference, DEFAULT_REFERENCE_CONSUMPTION)
            ?.takeIf { it in REFERENCE_RANGE } ?: return Parsed.Refused(Field.REFERENCE)
        return Parsed.Ok(
            Values(
                capacityKwh, healthPercent, powerKw, reservePercent, departurePercent,
                referenceConsumption,
            )
        )
    }

    fun read(context: Context): Values {
        val prefs = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val stored = Values(
            usableCapacityKwhWhenNew = prefs.getFloat(KEY_CAPACITY, Float.NaN).toDouble(),
            stateOfHealthPercent = prefs.getFloat(KEY_HEALTH, Float.NaN).toDouble(),
            minChargerPowerKw = prefs.getFloat(KEY_MIN_POWER, Float.NaN).toDouble(),
            reservePercent = prefs.getFloat(KEY_RESERVE, Float.NaN).toDouble(),
            departurePercent = prefs.getFloat(KEY_DEPARTURE, Float.NaN).toDouble(),
            referenceConsumptionKwhPer100Km = prefs.getFloat(KEY_REFERENCE, Float.NaN).toDouble(),
        )
        return sanitized(stored)
    }

    /**
     * The same figures with anything out of bounds replaced by its documented default.
     *
     * A value out of bounds comes from an older build, a corrupt preferences file or a
     * hand-edited settings file, and the default is a better answer than a plan built on it.
     */
    fun sanitized(values: Values): Values = Values(
        usableCapacityKwhWhenNew = values.usableCapacityKwhWhenNew
            .takeIf { it in CAPACITY_RANGE } ?: DEFAULT_CAPACITY_KWH,
        stateOfHealthPercent = values.stateOfHealthPercent
            .takeIf { it in HEALTH_RANGE } ?: DEFAULT_HEALTH_PERCENT,
        minChargerPowerKw = values.minChargerPowerKw
            .takeIf { it in MIN_POWER_RANGE } ?: DEFAULT_MIN_POWER_KW,
        reservePercent = values.reservePercent
            .takeIf { it in RESERVE_RANGE } ?: DEFAULT_RESERVE_PERCENT,
        departurePercent = values.departurePercent
            .takeIf { it in DEPARTURE_RANGE } ?: DEFAULT_DEPARTURE_PERCENT,
        referenceConsumptionKwhPer100Km = values.referenceConsumptionKwhPer100Km
            .takeIf { it in REFERENCE_RANGE } ?: DEFAULT_REFERENCE_CONSUMPTION,
    )

    fun write(context: Context, values: Values) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CAPACITY, values.usableCapacityKwhWhenNew.toFloat())
            .putFloat(KEY_HEALTH, values.stateOfHealthPercent.toFloat())
            .putFloat(KEY_MIN_POWER, values.minChargerPowerKw.toFloat())
            .putFloat(KEY_RESERVE, values.reservePercent.toFloat())
            .putFloat(KEY_DEPARTURE, values.departurePercent.toFloat())
            .putFloat(KEY_REFERENCE, values.referenceConsumptionKwhPer100Km.toFloat())
            .apply()
    }

    /** Back to the documented defaults, which is not the same as back to nothing. */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** Accepts the comma this dashboard's keyboard produces as readily as the point. */
    private fun number(text: String, ifBlank: Double): Double? {
        val trimmed = text.trim().replace(',', '.')
        if (trimmed.isEmpty()) return ifBlank
        return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** MG4 Long Range usable capacity from EVKX, by way of `AGENTS.md`. Not measured here. */
    const val DEFAULT_CAPACITY_KWH = 61.7

    /** Assumed intact, because nothing in this app has measured a pack's age. */
    const val DEFAULT_HEALTH_PERCENT = 100.0

    /** CP-048's constant: below this a mid-route stop is not a stop. */
    const val DEFAULT_MIN_POWER_KW = 22.0

    /** `ChargeStopPlan.DEFAULT_RESERVE_PERCENT`, restated where a driver can change it. */
    const val DEFAULT_RESERVE_PERCENT = 10.0

    /** `ChargeStopPlan.DEFAULT_DEPARTURE_PERCENT`, restated where a driver can change it. */
    const val DEFAULT_DEPARTURE_PERCENT = ChargeStopPlan.DEFAULT_DEPARTURE_PERCENT

    /**
     * [DEFAULT_CAPACITY_KWH] over the MG4 Long Range's 435 km WLTP range: the pack-side figure
     * the rating implies, which is what this app measures. The WLTP sheet's own 16.6 kWh/100 km
     * counts charging losses at the wall and would flatter every drive against it.
     */
    const val DEFAULT_REFERENCE_CONSUMPTION = 14.2

    /**
     * Whether a health figure may be stored at all.
     *
     * The estimator (CP-070) can hand back a figure outside this — a node bias in the energy
     * source moves the absolute, and a pack that measures at 112 % says more about where the
     * kilowatt-hours were counted than about the cells. Such a figure is reported on the
     * battery screen and never offered as a setting: a plan built on it would be wrong in the
     * optimistic direction.
     */
    fun isHealthAcceptable(percent: Double): Boolean =
        percent.isFinite() && percent in HEALTH_RANGE

    private val CAPACITY_RANGE = 10.0..200.0
    private val HEALTH_RANGE = 50.0..110.0
    private val MIN_POWER_RANGE = 3.0..400.0
    private val RESERVE_RANGE = 0.0..40.0
    private val DEPARTURE_RANGE = 20.0..100.0
    private val REFERENCE_RANGE = 5.0..50.0

    private const val FILE_NAME = "chargepilot_vehicle"
    private const val KEY_CAPACITY = "usable_capacity_kwh"
    private const val KEY_HEALTH = "state_of_health_percent"
    private const val KEY_MIN_POWER = "min_charger_power_kw"
    private const val KEY_RESERVE = "reserve_percent"
    private const val KEY_DEPARTURE = "departure_percent"
    private const val KEY_REFERENCE = "reference_consumption_kwh_per_100km"
}
