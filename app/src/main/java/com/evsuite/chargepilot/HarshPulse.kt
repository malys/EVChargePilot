package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EcoDrivingMonitor
import com.evsuite.hardware.telemetry.SpeedSlope

/**
 * CP-097: whether the driver is accelerating or braking hard right now, held a few seconds so a
 * glance can catch it.
 *
 * The rule is the trip's own (`EnergyTripAccumulator.countStyle`): the monitor's threshold either
 * sign on a two-second [SpeedSlope] (CP-099), nothing across a gap the app does not integrate
 * over, nothing while standing still. So the ring turns at exactly the seconds the trip counts.
 */
internal class HarshPulse(private val holdMs: Long = HOLD_MS) {

    enum class Kind { ACCELERATION, BRAKING }

    private val slope = SpeedSlope()
    private var lastAtMs = 0L
    private var lastKmh: Float? = null
    private var kind: Kind? = null
    private var untilMs = 0L

    /** Feed one snapshot; returns the pulse to show at [atMs], or null when calm. */
    fun add(atMs: Long, speedKmh: Float?): Kind? {
        if (lastKmh != null && atMs <= lastAtMs) return kind?.takeIf { atMs < untilMs }
        val from = lastKmh
        val ms2 = slope.add(atMs, speedKmh?.toDouble())
        if (ms2 != null && from != null && speedKmh != null && (from > 0f || speedKmh > 0f)) {
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
        lastAtMs = atMs
        lastKmh = speedKmh
        return kind?.takeIf { atMs < untilMs }
    }

    companion object {
        /** Long enough for a glance between two looks at the road, short enough to name one stop. */
        const val HOLD_MS = 3_000L
    }
}
