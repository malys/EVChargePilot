package com.evsuite.chargepilot

import android.app.Activity

/**
 * Stable channel: no evidence capture, by construction.
 *
 * The capture screen is a development instrument — it samples faster than the dashboard, writes
 * files a driver has no use for, and exists to answer questions about firmware behaviour. It is
 * not disabled here; it is not in the APK. Nothing in a stable build can be persuaded to start
 * recording property statistics.
 */
object EvidenceCaptureHook {
    const val IS_SUPPORTED = false

    /** Does nothing. */
    fun open(activity: Activity) = Unit
}
