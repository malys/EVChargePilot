package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.StoredTrip
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The game around the eco score: a number to beat, a record, a run to keep, and one weekly target.
 *
 * Everything is against the driver's own past, never against other drivers or a fixed ideal: the
 * only person this car can compare is the one driving it. And everything is measured — a part of
 * the score this trip could not measure is left out, and a trip with no score breaks nothing and
 * extends nothing.
 *
 * ponytail: scores are recomputed from today's fit, so an old trip's score can move a point as the
 * history grows. Storing the score at trip end fixes that, if a record ever visibly changes hands.
 */
data class EcoProgress(
    /** The newest trip's score, or null when it could not be scored. */
    val score: EcoScore?,
    /** Mean of up to [AVERAGE_TRIPS] scored trips before it. */
    val averageBefore: Int?,
    /** Best of the scored trips before it. */
    val personalBest: Int?,
    val isRecord: Boolean,
    /** Newest trips in a row, each scoring at or above the average of the trips before it. */
    val streak: Int,
    val challenge: Challenge?,
) {
    /**
     * Hard stops per 10 km, this week against last: the lever with the most energy behind it in
     * town, and one a driver can see themselves improve at within a week.
     */
    data class Challenge(
        val targetPer10Km: Double,
        val lastWeekPer10Km: Double,
        /** Null until this week has [MIN_CHALLENGE_KM] counted. */
        val thisWeekPer10Km: Double?,
    )

    companion object {
        const val AVERAGE_TRIPS = 10

        /** A record or a run over fewer trips than this is noise. */
        const val MIN_HISTORY = 3

        const val WEEK_MS = 7L * 24 * 3_600_000L

        /** Last week must have this much counted driving for a target to mean anything. */
        const val MIN_CHALLENGE_KM = 10.0

        /** A fifth fewer hard stops than last week, never below one every 20 km. */
        const val CHALLENGE_FACTOR = 0.8
        const val CHALLENGE_FLOOR_PER_10_KM = 0.5

        fun of(
            tripsNewestFirst: List<StoredTrip>,
            verdicts: Map<Long, EcoVerdict?>,
            nowMs: Long,
        ): EcoProgress {
            val scores = tripsNewestFirst.map { trip ->
                EcoScore.of(trip.summary, verdicts[trip.summary.startedAtMs])
            }
            val score = scores.firstOrNull()
            val before = scores.drop(1).filterNotNull().map { it.total }
            val average = before.take(AVERAGE_TRIPS).takeIf { it.size >= MIN_HISTORY }
                ?.average()?.roundToInt()
            val best = before.maxOrNull()?.takeIf { before.size >= MIN_HISTORY }
            return EcoProgress(
                score = score,
                averageBefore = average,
                personalBest = best,
                isRecord = score != null && best != null && score.total > best,
                streak = streak(scores),
                challenge = challenge(tripsNewestFirst, nowMs),
            )
        }

        private fun streak(scoresNewestFirst: List<EcoScore?>): Int {
            var run = 0
            for (index in scoresNewestFirst.indices) {
                val score = scoresNewestFirst[index] ?: break
                val earlier = scoresNewestFirst.drop(index + 1).filterNotNull().take(AVERAGE_TRIPS)
                if (earlier.size < MIN_HISTORY) break
                if (score.total < earlier.map { it.total }.average()) break
                run++
            }
            return run
        }

        private fun challenge(tripsNewestFirst: List<StoredTrip>, nowMs: Long): Challenge? {
            val last = per10Km(tripsNewestFirst, nowMs - 2 * WEEK_MS, nowMs - WEEK_MS) ?: return null
            val target = max(CHALLENGE_FLOOR_PER_10_KM, last * CHALLENGE_FACTOR)
            return Challenge(
                targetPer10Km = (target * 10).roundToInt() / 10.0,
                lastWeekPer10Km = last,
                thisWeekPer10Km = per10Km(tripsNewestFirst, nowMs - WEEK_MS, nowMs),
            )
        }

        private fun per10Km(trips: List<StoredTrip>, fromMs: Long, toMs: Long): Double? {
            var km = 0.0
            var brakings = 0
            trips.forEach { trip ->
                val summary = trip.summary
                if (summary.startedAtMs !in fromMs until toMs) return@forEach
                val counted = summary.harshBrakings ?: return@forEach
                km += summary.recordedDistanceKm ?: return@forEach
                brakings += counted
            }
            if (km < MIN_CHALLENGE_KM) return null
            return brakings / km * 10.0
        }
    }
}
