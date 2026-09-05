package com.evsuite.chargepilot

import android.content.Context

/**
 * Stable channel: nothing is recorded, because nothing here records.
 *
 * The call sites in the shared code read the same on both channels — one line per question,
 * next to the decision it is about — and on stable every one of them is this. The lambda is
 * never invoked, so a stable build does not even build the strings.
 */
object ValidationProbe {
    const val IS_SUPPORTED = false

    /** Always false: stable has no validation mode to turn on. */
    val isEnabled: Boolean get() = false

    /** Does nothing. */
    fun arm(context: Context) = Unit

    /** Does nothing, and does not evaluate [line]. */
    fun record(question: ValidationQuestion, line: () -> String) = Unit
}
