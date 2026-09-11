package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.Provenance
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.UnavailableReason
import java.util.Locale

// The unit belongs to the value, not to the screen: the dashboard and the diagnostics report
// print the same figure the same way.
internal const val PATTERN_SOC = "%.1f %%"
internal const val PATTERN_SPEED = "%.0f km/h"
internal const val PATTERN_POWER = "%+.1f kW"
internal const val PATTERN_TEMP = "%.0f °C"
internal const val PATTERN_DISTANCE = "%.1f km"
internal const val PATTERN_ENERGY = "%.2f kWh"
internal const val PATTERN_CONSUMPTION = "%.1f kWh/100 km"

// The same two figures where a decimal would be a promise the reading cannot keep: a charge
// forecast good to a tenth of a percent, a reach good to a hundred metres. Same units, one
// digit fewer, and still declared here so a screen never spells a unit out on its own.
internal const val PATTERN_SOC_WHOLE = "%.0f %%"
internal const val PATTERN_DISTANCE_WHOLE = "%.0f km"

/**
 * Draws a [Provenanced] value so its kind is visible without a legend.
 *
 * The distinction is carried by the text itself, not by colour: an estimate wears a `≈` and
 * its band, an unavailable figure is an em dash whose reason is spelled out in the reading's
 * description and in the diagnostics report. That keeps the daylight contrast floor intact
 * and keeps the meaning available to a screen reader, which a coloured dot would not.
 */
class ProvenanceText(private val context: Context) {

    /**
     * @param pattern a `String.format` pattern carrying the unit, e.g. `"%.1f %%"`.
     * @param unavailable what stands in for a missing value; the unit-bearing fields keep
     *   their unit so the layout does not shift when the vehicle starts answering.
     */
    fun render(
        value: Provenanced<out Any>,
        pattern: String,
        unavailable: String = DASH,
    ): String = when (value.provenance) {
        Provenance.UNAVAILABLE -> unavailable
        Provenance.MEASURED, Provenance.DERIVED -> format(pattern, value.value!!)
        Provenance.ESTIMATED -> context.getString(
            R.string.value_estimated,
            format(pattern, value.value!!),
            format(pattern, value.uncertainty!!),
        )
    }

    /** Same rules, for a value whose text is not a `String.format` pattern (a duration). */
    fun <T : Any> renderWith(
        value: Provenanced<T>,
        unavailable: String = DASH,
        transform: (T) -> String,
    ): String = value.value?.let(transform) ?: unavailable

    /** What the reading is, and — when it is missing — why. Used for accessibility and diagnostics. */
    fun describe(label: String, value: Provenanced<out Any>, rendered: String): String {
        val kind = context.getString(
            when (value.provenance) {
                Provenance.MEASURED -> R.string.provenance_measured
                Provenance.DERIVED -> R.string.provenance_derived
                Provenance.ESTIMATED -> R.string.provenance_estimated
                Provenance.UNAVAILABLE -> R.string.provenance_unavailable
            }
        )
        val reason = value.reason?.let { context.getString(reasonRes(it)) }
        return if (reason == null) {
            context.getString(R.string.provenance_description, label, rendered, kind)
        } else {
            context.getString(R.string.provenance_description_missing, label, kind, reason)
        }
    }

    /** Every reading of a frame, described: what it is, and when it is missing, why. */
    fun describeAll(readings: DashboardReadings): List<String> {
        val numeric = listOf(
            Triple(R.string.label_soc, readings.soc, PATTERN_SOC),
            Triple(R.string.label_range, readings.range, PATTERN_DISTANCE),
            Triple(R.string.label_adaptive_range, readings.adaptiveRange, PATTERN_DISTANCE),
            Triple(R.string.label_speed, readings.speed, PATTERN_SPEED),
            Triple(R.string.label_power, readings.power, PATTERN_POWER),
            Triple(R.string.label_outside_temp, readings.climate.outsideTemp, PATTERN_TEMP),
            Triple(R.string.label_cabin_temp, readings.climate.cabinTemp, PATTERN_TEMP),
            Triple(R.string.label_battery_temp, readings.climate.batteryTemp, PATTERN_TEMP),
            Triple(
                R.string.label_climate_driver_target,
                readings.climate.driverTarget,
                PATTERN_TEMP,
            ),
            Triple(
                R.string.label_climate_passenger_target,
                readings.climate.passengerTarget,
                PATTERN_TEMP,
            ),
            Triple(
                R.string.label_instant_consumption,
                readings.instantConsumption,
                PATTERN_CONSUMPTION,
            ),
            Triple(R.string.label_distance, readings.tripDistance, PATTERN_DISTANCE),
            Triple(R.string.label_energy_used, readings.tripEnergy, PATTERN_ENERGY),
            Triple(R.string.label_regenerated, readings.tripRegen, PATTERN_ENERGY),
            Triple(
                R.string.label_trip_average_consumption,
                readings.tripConsumption,
                PATTERN_CONSUMPTION,
            ),
        ).map { (label, value, pattern) ->
            describe(context.getString(label), value, render(value, pattern))
        }
        val states = listOf(
            R.string.label_climate_power to readings.climate.hvacOn,
            R.string.label_climate_ac to readings.climate.acOn,
            R.string.label_climate_auto to readings.climate.autoOn,
            R.string.label_climate_econ to readings.climate.econOn,
            R.string.label_climate_recirculation to readings.climate.recirculationOn,
        ).map { (label, value) ->
            val rendered = renderWith(value) {
                context.getString(if (it) R.string.state_on else R.string.state_off)
            }
            describe(context.getString(label), value, rendered)
        }
        val fan = renderWith(readings.climate.fan) {
            context.getString(R.string.climate_fan_value, it.level, it.maximum)
        }
        return numeric + states + describe(
            context.getString(R.string.label_climate_fan),
            readings.climate.fan,
            fan,
        )
    }

    private fun reasonRes(reason: UnavailableReason): Int = when (reason) {
        UnavailableReason.UNSUPPORTED_FIRMWARE -> R.string.reason_unsupported_firmware
        UnavailableReason.UNVALIDATED_FIRMWARE -> R.string.reason_unvalidated_firmware
        UnavailableReason.SIGNAL_ABSENT -> R.string.reason_signal_absent
        UnavailableReason.INSUFFICIENT_SAMPLES -> R.string.reason_insufficient_samples
        UnavailableReason.SPEED_TOO_LOW -> R.string.reason_speed_too_low
        UnavailableReason.MODEL_NOT_TRAINED -> R.string.reason_model_not_trained
    }

    private fun format(pattern: String, value: Any): String =
        String.format(Locale.getDefault(), pattern, value)

    private companion object {
        const val DASH = "—"
    }
}

/**
 * True where this generation declares battery power and has never been seen publishing it.
 *
 * A screen that needs battery power has two different silences to report, and only one of them
 * is a wait. "Not validated yet" invites a driver to keep coming back to a screen that will
 * never fill in; CP-003's evidence already knows the difference, so the screens say it.
 */
fun batteryPowerNeverPublished(): Boolean = CarPropertyEvidence.isNeverPublished(
    CarPropertyEvidence.Signal.BATTERY_POWER_KW,
    FirmwareInfo.getGeneration(),
)
