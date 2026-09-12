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
     * One check per process. The activity is recreated on a configuration change, and a
     * release lookup per recreation is a request GitHub counts and the driver never asked for.
     */
    private val checked = AtomicBoolean(false)

    /** Arbitrary, and never read back: the grant is re-checked at the moment of writing. */
    private const val STORAGE_REQUEST = 0xE7A

    /** Fire-and-forget. Every network and disk step runs off the main thread. */
    fun checkInBackground(activity: Activity) {
        if (!checked.compareAndSet(false, true)) return
        requestDownloadsAccess(activity)
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

    private fun deliver(context: Context, activity: Activity) {
        val apk = findOrFetch(context) ?: return
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) announce(activity, apk)
        }
    }

    /**
     * The published APK for a newer build, or null when there is none, when anything refused,
     * or when the check simply could not run. A failed update check is not an error the driver
     * has to see: the dashboard is the app, and this channel is a convenience on top of it.
     */
    private fun findOrFetch(context: Context): File? {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return null

        val update = OtaUpdater.check(current) ?: return null

        // A build already downloaded is not downloaded again: the check runs at every start,
        // and an unstable tester who has not installed yesterday's APK would otherwise pull
        // the same ~5 MB on every launch. The folder is resolved first, so a build that landed
        // in the fallback directory while the grant was still pending is fetched once more, to
        // the `Download` folder the driver was told to look in. Self-correcting beats a file
        // stranded where the dialog no longer points.
        val directory = OtaUpdater.downloadDirectory(context) ?: return null
        val existing = File(directory, OtaUpdater.fileName(update.versionName))
        if (existing.isFile && existing.length() > 0) return existing

        val downloaded = OtaUpdater.download(context, update) ?: return null
        val published = OtaUpdater.publish(context, downloaded, update.versionName)
        if (published != null) AppLogger.i(TAG, "Update ${update.versionName} ready at $published")
        return published
    }

    private fun announce(activity: Activity, apk: File) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_ready, apk.name, apk.parent))
            .setPositiveButton(R.string.update_close, null)
            .show()
    }
}
