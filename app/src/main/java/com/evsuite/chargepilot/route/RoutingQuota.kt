package com.evsuite.chargepilot.route

/**
 * What is left of the routing quota, counted here so the app refuses before the server does.
 *
 * OpenRouteService allows 2000 directions requests per day and 40 in any rolling 60 seconds;
 * over the day it returns `403`, over the minute `429`. Both numbers were read from the ORS FAQ
 * on 2026-09-04 and both are generous for a driver and trivial for a timer, which is the real
 * risk: a recompute on a tick would exhaust a day's allowance in under an hour and leave the
 * driver with a feature that stopped working for reasons nothing on screen explains.
 *
 * The day window rolls from the first request rather than from midnight, which is ORS's own
 * rule and the reason this counts timestamps instead of resetting a counter.
 *
 * Not persisted deliberately: a restart forgetting spent requests errs towards trying and
 * being refused by the server, which is visible, rather than towards refusing locally on a
 * count that may be wrong. [observe] lets a real `x-ratelimit-remaining` correct the guess.
 */
class RoutingQuota(
    private val dayLimit: Int = DIRECTIONS_PER_DAY,
    private val minuteLimit: Int = DIRECTIONS_PER_MINUTE,
) {

    private val spent = ArrayDeque<Long>()

    /** Fewer than the day's count if the server has told us otherwise. */
    private var remainingFromServer: Int? = null

    sealed interface Verdict {
        object Allowed : Verdict

        /** Refused locally, with the wait in seconds — a number the screen can show. */
        data class Wait(val seconds: Long, val window: Window) : Verdict
    }

    enum class Window { MINUTE, DAY }

    @Synchronized
    fun check(nowMs: Long): Verdict {
        prune(nowMs)
        val inMinute = spent.count { nowMs - it < MINUTE_MS }
        if (inMinute >= minuteLimit) {
            val oldest = spent.first { nowMs - it < MINUTE_MS }
            return Verdict.Wait(secondsUntil(oldest + MINUTE_MS, nowMs), Window.MINUTE)
        }
        val serverExhausted = remainingFromServer?.let { it <= 0 } ?: false
        if (spent.size >= dayLimit || serverExhausted) {
            val oldest = spent.firstOrNull() ?: nowMs
            return Verdict.Wait(secondsUntil(oldest + DAY_MS, nowMs), Window.DAY)
        }
        return Verdict.Allowed
    }

    @Synchronized
    fun record(nowMs: Long) {
        prune(nowMs)
        spent.addLast(nowMs)
        remainingFromServer = remainingFromServer?.minus(1)
    }

    /** The server's own count wins over ours: it knows about other clients on the same key. */
    @Synchronized
    fun observe(remaining: Int?) {
        if (remaining != null && remaining >= 0) remainingFromServer = remaining
    }

    @Synchronized
    fun spentToday(nowMs: Long): Int {
        prune(nowMs)
        return spent.size
    }

    private fun prune(nowMs: Long) {
        while (spent.isNotEmpty() && nowMs - spent.first() >= DAY_MS) spent.removeFirst()
    }

    private fun secondsUntil(whenMs: Long, nowMs: Long): Long =
        maxOf(1L, (whenMs - nowMs + 999L) / 1000L)

    companion object {
        /** ORS FAQ, verified 2026-09-04. Endpoint limits differ; these are the directions ones. */
        const val DIRECTIONS_PER_DAY = 2000
        const val DIRECTIONS_PER_MINUTE = 40

        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
