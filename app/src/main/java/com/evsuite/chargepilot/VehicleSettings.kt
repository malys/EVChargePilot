package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.BatteryCapacityConfig

/**
 * The four numbers about *this* car and *this* driver that the app used to answer for them.
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
     */
    data class Values(
        val usableCapacityKwhWhenNew: Double = DEFAULT_CAPACITY_KWH,
        val stateOfHealthPercent: Double = DEFAULT_HEALTH_PERCENT,
        val minChargerPowerKw: Double = DEFAULT_MIN_POWER_KW,
        val reservePercent: Double = DEFAULT_RESERVE_PERCENT,
    ) {
        val pack: BatteryCapacityConfig
            get() = BatteryCapacityConfig(usableCapacityKwhWhenNew, stateOfHealthPercent)

        /** True while every figure is still the specification sheet's rather than the driver's. */
        val isDefault: Boolean get() = this == Values()
    }

    /** Which field a driver has to fix, so the screen can say so instead of failing silently. */
    enum class Field { CAPACITY, HEALTH, MIN_POWER, RESERVE }

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
    ): Parsed {
        val capacityKwh = number(capacity, DEFAULT_CAPACITY_KWH)
            ?.takeIf { it in CAPACITY_RANGE } ?: return Parsed.Refused(Field.CAPACITY)
        val healthPercent = number(health, DEFAULT_HEALTH_PERCENT)
            ?.takeIf { it in HEALTH_RANGE } ?: return Parsed.Refused(Field.HEALTH)
        val powerKw = number(minPower, DEFAULT_MIN_POWER_KW)
            ?.takeIf { it in MIN_POWER_RANGE } ?: return Parsed.Refused(Field.MIN_POWER)
        val reservePercent = number(reserve, DEFAULT_RESERVE_PERCENT)
            ?.takeIf { it in RESERVE_RANGE } ?: return Parsed.Refused(Field.RESERVE)
        return Parsed.Ok(Values(capacityKwh, healthPercent, powerKw, reservePercent))
    }

    fun read(context: Context): Values {
        val prefs = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val stored = Values(
            usableCapacityKwhWhenNew = prefs.getFloat(KEY_CAPACITY, Float.NaN).toDouble(),
            stateOfHealthPercent = prefs.getFloat(KEY_HEALTH, Float.NaN).toDouble(),
            minChargerPowerKw = prefs.getFloat(KEY_MIN_POWER, Float.NaN).toDouble(),
            reservePercent = prefs.getFloat(KEY_RESERVE, Float.NaN).toDouble(),
        )
        // A stored value out of bounds is a value from an older build or a corrupt file, and
        // the documented default is a better answer than a plan built on it.
        return Values(
            usableCapacityKwhWhenNew = stored.usableCapacityKwhWhenNew
                .takeIf { it in CAPACITY_RANGE } ?: DEFAULT_CAPACITY_KWH,
            stateOfHealthPercent = stored.stateOfHealthPercent
                .takeIf { it in HEALTH_RANGE } ?: DEFAULT_HEALTH_PERCENT,
            minChargerPowerKw = stored.minChargerPowerKw
                .takeIf { it in MIN_POWER_RANGE } ?: DEFAULT_MIN_POWER_KW,
            reservePercent = stored.reservePercent
                .takeIf { it in RESERVE_RANGE } ?: DEFAULT_RESERVE_PERCENT,
        )
    }

    fun write(context: Context, values: Values) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CAPACITY, values.usableCapacityKwhWhenNew.toFloat())
            .putFloat(KEY_HEALTH, values.stateOfHealthPercent.toFloat())
            .putFloat(KEY_MIN_POWER, values.minChargerPowerKw.toFloat())
            .putFloat(KEY_RESERVE, values.reservePercent.toFloat())
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

    private val CAPACITY_RANGE = 10.0..200.0
    private val HEALTH_RANGE = 50.0..110.0
    private val MIN_POWER_RANGE = 3.0..400.0
    private val RESERVE_RANGE = 0.0..40.0

    private const val FILE_NAME = "chargepilot_vehicle"
    private const val KEY_CAPACITY = "usable_capacity_kwh"
    private const val KEY_HEALTH = "state_of_health_percent"
    private const val KEY_MIN_POWER = "min_charger_power_kw"
    private const val KEY_RESERVE = "reserve_percent"
}
