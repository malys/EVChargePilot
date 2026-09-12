package com.evsuite.chargepilot.update

import android.app.Activity

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
}
