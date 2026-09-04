package com.evsuite.chargepilot

import kotlin.math.abs

/**
 * Compares a distance integrated from speed against one nobody derived from speed.
 *
 * A trip distance is `∫ speed dt`, so a wrong speed unit scales it by exactly the same factor
 * and nothing in the number says so — a 2.4 km drive recorded as more than 7 km is the only
 * symptom, and it needs somebody to notice. The navigation adapter's odometer is an
 * independent reading: its span over the same period is a real distance, and the ratio
 * between the two is the scale error, spelled out.
 *
 * Android-free so the arithmetic that decides a unit question is covered by tests rather than
 * done by hand on a bundle.
 */
internal object SpeedScaleCheck {

    /** How far the ratio may sit from a candidate before it stops counting as that candidate. */
    private const val TOLERANCE = 0.25

    /** m/s read as km/h. The one wrong scale this code can actually produce. */
    private const val MPS_AS_KMH = 3.6

    /** Below this the odometer's integer kilometres are too coarse to conclude anything. */
    private const val MIN_ODOMETER_SPAN_KM = 2.0

    enum class Verdict {
        /** No odometer span, no trips, or too short a distance to tell. */
        INCONCLUSIVE,

        /** The two distances agree. The speed scale is right. */
        CONSISTENT,

        /** Integrated distance is about 3.6× the odometer's: speed is km/h read as m/s. */
        SPEED_SCALED_BY_3_6,

        /** They disagree by something that is not the m/s factor. Neither is trusted. */
        DISAGREES,
    }

    data class Result(
        val verdict: Verdict,
        val odometerSpanKm: Double?,
        val integratedKm: Double?,
        /** integrated / odometer, or null when either side is missing. */
        val ratio: Double?,
        val note: String,
    )

    fun of(odometerSpanKm: Double?, integratedKm: Double?): Result {
        if (odometerSpanKm == null || integratedKm == null ||
            odometerSpanKm < MIN_ODOMETER_SPAN_KM
        ) {
            return Result(
                verdict = Verdict.INCONCLUSIVE,
                odometerSpanKm = odometerSpanKm,
                integratedKm = integratedKm,
                ratio = null,
                note = "Needs an adapter-odometer span of at least " +
                    "$MIN_ODOMETER_SPAN_KM km and at least one recorded trip in the same " +
                    "session. Drive a few kilometres with the app running, then export.",
            )
        }
        val ratio = integratedKm / odometerSpanKm
        val verdict = when {
            abs(ratio - 1.0) <= TOLERANCE -> Verdict.CONSISTENT
            abs(ratio - MPS_AS_KMH) <= TOLERANCE * MPS_AS_KMH -> Verdict.SPEED_SCALED_BY_3_6
            else -> Verdict.DISAGREES
        }
        return Result(
            verdict = verdict,
            odometerSpanKm = odometerSpanKm,
            integratedKm = integratedKm,
            ratio = ratio,
            note = when (verdict) {
                Verdict.CONSISTENT ->
                    "Integrated distance matches the adapter odometer. The km/h conversion " +
                        "in EVHardware.getVehicleSpeedKmh is right on this firmware."
                Verdict.SPEED_SCALED_BY_3_6 ->
                    "Integrated distance is about 3.6x the odometer's. PERF_VEHICLE_SPEED " +
                        "already reports km/h on this firmware and the m/s conversion in " +
                        "EVHardware.getVehicleSpeedKmh multiplies it a second time."
                Verdict.DISAGREES ->
                    "The two distances disagree by something other than the m/s factor. " +
                        "Neither is trusted until the odometer reading itself is validated."
                Verdict.INCONCLUSIVE -> ""
            },
        )
    }
}
