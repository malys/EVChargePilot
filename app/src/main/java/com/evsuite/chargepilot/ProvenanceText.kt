package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.hardware.telemetry.Provenance
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.UnavailableReason
import java.util.Locale

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
            format(BAND_PATTERN, value.uncertainty!!),
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
        const val BAND_PATTERN = "%.1f"
    }
}
