package com.evsuite.chargepilot

enum class ParkedDeletionGate { PARKED, MOVING, SPEED_UNAVAILABLE }

/** File deletion is a parked-only driver action and fails closed on every unreadable speed. */
object ParkedDeletionPolicy {
    fun gate(speedKmh: Float?, observedAtMs: Long?, nowMs: Long): ParkedDeletionGate = when {
        speedKmh == null || !speedKmh.isFinite() || speedKmh < 0f ||
            observedAtMs == null || nowMs < observedAtMs ||
            nowMs - observedAtMs > MAX_READING_AGE_MS ->
            ParkedDeletionGate.SPEED_UNAVAILABLE
        speedKmh > MAX_PARKED_SPEED_KMH -> ParkedDeletionGate.MOVING
        else -> ParkedDeletionGate.PARKED
    }

    const val MAX_READING_AGE_MS = 2_500L
    private const val MAX_PARKED_SPEED_KMH = 0.1f
}
