package com.evsuite.chargepilot

enum class PrimaryPage {
    ENERGY,
    ARRIVAL,
    TRIPS,
    BATTERY,
    DIAGNOSTICS,
    SETTINGS;

    /** The page a swipe reaches. Diagnostics has no tab, so a swipe never lands on it. */
    fun neighbour(offset: Int): PrimaryPage? {
        val pages = entries.filter { it != DIAGNOSTICS }
        val index = pages.indexOf(this)
        return if (index < 0) null else pages.getOrNull(index + offset)
    }
}

/**
 * Diagnostics is a support page, not a driver's one: it has no tab. Three quick taps on the app
 * name open it — EVTasker's console gesture — and the count starts again after each opening.
 */
internal class DiagnosticsTaps(private val windowMs: Long = WINDOW_MS) {
    private var taps = 0
    private var firstAtMs = 0L

    /** Counts a tap at [nowMs]; true on the third within [windowMs] of the first. */
    fun onTap(nowMs: Long): Boolean {
        if (taps == 0 || nowMs - firstAtMs > windowMs) {
            taps = 0
            firstAtMs = nowMs
        }
        taps++
        if (taps < REQUIRED_TAPS) return false
        taps = 0
        return true
    }

    private companion object {
        const val REQUIRED_TAPS = 3
        const val WINDOW_MS = 2_000L
    }
}
