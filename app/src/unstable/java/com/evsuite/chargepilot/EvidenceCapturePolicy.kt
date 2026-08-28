package com.evsuite.chargepilot

internal enum class EvidenceCaptureGate { PARKED, MOVING, SPEED_UNAVAILABLE }

/** Android-free safety and cadence decisions, covered by the unstable variant's JVM tests. */
internal object EvidenceCapturePolicy {
    private const val PARKED_KMH = 0.1f
    private const val BURST_WINDOW_MS = 60_000L
    private const val BURST_PERIOD_MS = 200L
    private const val NORMAL_PERIOD_MS = 1_000L

    fun gate(speedKmh: Float?): EvidenceCaptureGate = when {
        speedKmh == null || !speedKmh.isFinite() -> EvidenceCaptureGate.SPEED_UNAVAILABLE
        speedKmh <= PARKED_KMH -> EvidenceCaptureGate.PARKED
        else -> EvidenceCaptureGate.MOVING
    }

    fun shouldRead(recording: Boolean, captureElapsedMs: Long, sinceLastReadMs: Long?): Boolean {
        if (sinceLastReadMs == null) return true
        val period = if (recording && captureElapsedMs < BURST_WINDOW_MS) {
            BURST_PERIOD_MS
        } else {
            NORMAL_PERIOD_MS
        }
        return sinceLastReadMs >= period
    }
}
