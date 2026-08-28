package com.evsuite.chargepilot

import android.app.Activity
import android.content.Intent

/** Unstable channel: the capture screen exists and is reachable from the dashboard. */
object EvidenceCaptureHook {
    const val IS_SUPPORTED = true

    fun open(activity: Activity) {
        activity.startActivity(Intent(activity, EvidenceCaptureActivity::class.java))
    }
}
