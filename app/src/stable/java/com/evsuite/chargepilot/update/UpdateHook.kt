package com.evsuite.chargepilot.update

import android.app.Activity
import android.content.Context

/**
 * Stable channel: no self-update, by construction.
 *
 * This is not a disabled feature — the updater is not in the APK at all. A stable build
 * carries no release URL, opens no socket to GitHub and writes no APK anywhere, and no
 * preference can persuade it to. Stable users install a new version themselves.
 */
object UpdateHook {
    /** Does nothing. Stable users install a new version themselves. */
    fun checkInBackground(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit

    /**
     * Says so in the diagnostic report, because "the update never appears" has this as its
     * first and commonest cause: the APK on the car is the stable one, which contains no
     * updater at all. Without this line the report is silent and indistinguishable from an
     * unstable build whose check failed.
     */
    fun diagnosis(@Suppress("UNUSED_PARAMETER") context: Context): List<String> = listOf(
        "channel=stable",
        "updater_present=false",
    )
}
