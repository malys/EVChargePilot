package com.evsuite.chargepilot

/** Read-only warning when a planned charging stop cannot reach its assumed departure charge. */
internal data class ChargeLimitAdvice(
    val limitPercent: Int,
    val departurePercent: Double,
) {
    companion object {
        fun of(
            usesDepartureCharge: Boolean,
            departurePercent: Double,
            limitPercent: Int?,
        ): ChargeLimitAdvice? {
            if (!usesDepartureCharge) return null
            val limit = limitPercent?.takeIf { it in 0..100 } ?: return null
            if (!departurePercent.isFinite() || departurePercent !in 0.0..100.0) return null
            if (departurePercent <= limit.toDouble()) return null
            return ChargeLimitAdvice(limit, departurePercent)
        }
    }
}
