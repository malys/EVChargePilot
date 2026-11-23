package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EcoDrivingMonitor

/**
 * CP-097: whether the driver is accelerating or braking hard right now, held a few seconds so a
 * glance can catch it.
 *
 * The rule is the trip's own (`EnergyTripAccumulator.countStyle`): the monitor's threshold either
 * sign, nothing across a gap the app does not integrate over, nothing while standing still. So the
 * ring turns at exactly the seconds the anticipation and smoothness parts of the score count.
 */
internal class HarshPulse(private val holdMs: Long = HOLD_MS) {

    enum class Kind { ACCELERATION, BRAKING }

    private var lastAtMs = 0L
    private var lastKmh: Float? = null
    private var kind: Kind? = null
    private var untilMs = 0L

    /** Feed one snapshot; returns the pulse to show at [atMs], or null when calm. */
    fun add(atMs: Long, speedKmh: Float?): Kind? {
        val from = lastKmh
        val gapMs = atMs - lastAtMs
        if (from != null && speedKmh != null && gapMs in 1..EcoDrivingMonitor.MAX_GAP_MS &&
            (from > 0f || speedKmh > 0f)
        ) {
            val ms2 = (speedKmh - from) / KMH_PER_MS2 / (gapMs / 1000.0)
            val hard = when {
                ms2 >= EcoDrivingMonitor.HARSH_ACCELERATION_MS2 -> Kind.ACCELERATION
                ms2 <= -EcoDrivingMonitor.HARSH_ACCELERATION_MS2 -> Kind.BRAKING
                else -> null
            }
            if (hard != null) {
                kind = hard
                untilMs = atMs + holdMs
            }
        }
        if (gapMs > 0L || from == null) {
            lastAtMs = atMs
            lastKmh = speedKmh
        }
        return kind?.takeIf { atMs < untilMs }
    }

    companion object {
        /** Long enough for a glance between two looks at the road, short enough to name one stop. */
        const val HOLD_MS = 3_000L
        private const val KMH_PER_MS2 = 3.6
    }
}
