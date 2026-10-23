package com.evsuite.chargepilot

/** Monotonic-time decisions for the embedded surface; a lit frame never ends the watch. */
internal class MapFrameWatch(private val limitMs: Long, startedAtMs: Long) {
    data class Sample(val state: String, val changed: Boolean, val expired: Boolean)

    private var blackSince: Long? = startedAtMs
    private var lastPicture: Int? = null
    private var identical = 0
    private var previous: String? = null

    fun sample(pixels: IntArray?, nowMs: Long): Sample {
        val black = pixels == null || MapPane.isBlack(pixels)
        blackSince = if (black) blackSince ?: nowMs else null
        val picture = pixels?.contentHashCode()
        identical = if (!black && picture == lastPicture) identical + 1 else 0
        lastPicture = picture
        val state = when {
            pixels == null -> "no frame"
            black -> "black"
            identical >= 3 -> "frozen"
            else -> "map"
        }
        return Sample(state, state != previous,
            blackSince?.let { nowMs - it >= limitMs } == true).also { previous = state }
    }
}
