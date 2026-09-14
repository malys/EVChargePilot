package com.evsuite.chargepilot.update

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.evsuite.chargepilot.R
import com.evsuite.hardware.AppLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unstable channel: check GitHub's rolling pre-release at start, download a newer build and
 * tell the driver where it is.
 *
 * It never installs. EVChargePilot holds no install capability, so the update becomes a file
 * in the download folder and a sentence saying so; the install is the driver's own tap in the
 * head unit's package installer. Stable contains none of this — see the stable [UpdateHook].
 */
object UpdateHook {

    private const val TAG = "EV_UPDATE"

    /**
     * One check per answer, not one per process. A release lookup on every visit to the
     * dashboard is a request GitHub counts and the driver never asked for, so this latches —
     * but it is released again when the pipeline gave up without an answer, because a head unit
     * whose network arrived late would otherwise stay on an old build until the app is killed.
     */
    private val checked = AtomicBoolean(false)

    /**
     * The storage prompt is a one-shot for the process. A refusal is a supported state — the
     * APK goes to the app's own folder — not a question to ask again on every visit.
     */
    private val askedForDownloads = AtomicBoolean(false)

    /** Arbitrary, and never read back: the grant is re-checked at the moment of writing. */
    private const val STORAGE_REQUEST = 0xE7A

    /** Fire-and-forget. Every network and disk step runs off the main thread. */
    fun checkInBackground(activity: Activity) {
        if (!checked.compareAndSet(false, true)) return
        if (askedForDownloads.compareAndSet(false, true)) requestDownloadsAccess(activity)
        val context = activity.applicationContext
        // The thread holds the activity until its timeouts expire, which is what lets it
        // speak on the screen the driver is already looking at. Bounded, and the dialog is
        // skipped when that screen has gone.
        Thread({ deliver(context, activity) }, "chargepilot-ota").start()
    }

    /**
     * Asks for the `Download` folder, on the dashboard the tester opened.
     *
     * Unstable only, and the only screen this app has at start; a service cannot ask and a
     * permission that is never asked for is a permission the app does not have. Nothing waits
     * on the answer: the check is already several seconds of network away from needing it, and
     * the grant is re-read at the moment of writing rather than remembered from here. A refusal
     * is a supported state — the APK goes to this app's own `Download` directory instead, and
     * the dialog names whichever path it used.
     *
     * From API 29 the permission does not exist for us (`maxSdkVersion`), because scoped
     * storage refuses that write whatever is granted. The target head unit is API 28.
     */
    private fun requestDownloadsAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(activity, arrayOf(permission), STORAGE_REQUEST)
    }

    /**
     * A vehicle head unit rarely has a network the instant this activity is created — Wi-Fi or
     * a tethered phone is still connecting while the app is already on screen. Two retries,
     * 20s apart, cover that without turning into a poll: only [Attempt.Retry] — the request
     * never reaching GitHub at all — is retried. A build that IS current answers on the first
     * try and stops there, so an ordinary launch with a healthy network still makes exactly
     * one GitHub call, not three.
     */
    private val RETRY_DELAYS_MS = longArrayOf(20_000L, 20_000L)

    /** One pass over the whole pipeline, or the reason nothing is ready yet. */
    private sealed interface Attempt {
        data class Ready(val apk: File) : Attempt
        object Nothing : Attempt

        /** The check or the download never reached the network — worth trying again. */
        object Retry : Attempt
    }

    private fun deliver(context: Context, activity: Activity) {
        var attempt = attempt(context)
        for (delay in RETRY_DELAYS_MS) {
            if (attempt !is Attempt.Retry) break
            Thread.sleep(delay)
            attempt = attempt(context)
        }
        if (attempt is Attempt.Retry) {
            // Still nothing after the retries: the network was not there yet. Release the latch
            // so the next visit to the dashboard asks again, instead of leaving the tester on an
            // old build for the rest of the process because Wi-Fi came up a minute too late.
            checked.set(false)
            return
        }
        val apk = (attempt as? Attempt.Ready)?.apk ?: return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) announce(activity, apk)
        }
    }

    /**
     * The published APK for a newer build, [Attempt.Nothing] when there is none or anything
     * refused, or [Attempt.Retry] when the network step itself never completed. A failed
     * update check is not an error the driver has to see: the dashboard is the app, and this
     * channel is a convenience on top of it.
     */
    private fun attempt(context: Context): Attempt {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return Attempt.Nothing

        val update = when (val result = OtaUpdater.check(current)) {
            OtaUpdater.CheckResult.Unreachable -> return Attempt.Retry
            is OtaUpdater.CheckResult.Answered -> result.update ?: return Attempt.Nothing
        }

        // A build already downloaded is not downloaded again: the check runs at every start,
        // and an unstable tester who has not installed yesterday's APK would otherwise pull
        // the same ~5 MB on every launch. The folder is resolved first, so a build that landed
        // in the fallback directory while the grant was still pending is fetched once more, to
        // the `Download` folder the driver was told to look in. Self-correcting beats a file
        // stranded where the dialog no longer points.
        val directory = OtaUpdater.downloadDirectory(context) ?: return Attempt.Nothing
        val existing = File(directory, OtaUpdater.fileName(update.versionName))
        if (existing.isFile && existing.length() > 0) return Attempt.Ready(existing)

        val downloaded = when (val result = OtaUpdater.download(context, update)) {
            OtaUpdater.DownloadResult.Unreachable -> return Attempt.Retry
            OtaUpdater.DownloadResult.Refused -> return Attempt.Nothing
            is OtaUpdater.DownloadResult.Downloaded -> result.file
        }
        val published = OtaUpdater.publish(context, downloaded, update.versionName)
            ?: return Attempt.Nothing
        AppLogger.i(TAG, "Update ${update.versionName} ready at $published")
        return Attempt.Ready(published)
    }

    private fun announce(activity: Activity, apk: File) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_ready, apk.name, apk.parent))
            .setPositiveButton(R.string.update_close, null)
            .show()
    }
}
