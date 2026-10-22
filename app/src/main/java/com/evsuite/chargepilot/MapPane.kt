package com.evsuite.chargepilot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import com.evsuite.hardware.AppLogger
import java.lang.reflect.InvocationTargetException

/**
 * CP-094: the car's own map, hosted inside EVChargePilot on a virtual display.
 *
 * `android.app.ActivityView` is hidden on Android 9, so it is reached by reflection. A
 * platform-signed APK is exempt from the hidden-API checks, and the 2026-10-18 bundle read
 * `platform_signed=true`. No navigation activity on this car allows embedding, so the launch is
 * allowed only because the app holds `INTERNAL_SYSTEM_WINDOW`, which is signature-level and
 * granted to a platform-signed build that declares it.
 *
 * **Every failure is the old handoff.** A missing class, a refused permission or a launch the
 * framework throws on all come back through [start]'s callback, and the screen falls back to
 * the full-screen map. The pane is never left black on purpose.
 */
@SuppressLint("PrivateApi")
internal class MapPane(private val context: Context) {

    /** Null when this firmware has no `ActivityView` or refuses to build one. */
    val view: ViewGroup? = runCatching {
        Class.forName(ACTIVITY_VIEW).getConstructor(Context::class.java)
            .newInstance(context) as ViewGroup
    }.onFailure { AppLogger.w(TAG, "map pane: no ActivityView: ${it.javaClass.simpleName}") }
        .getOrNull()

    private val main = Handler(Looper.getMainLooper())
    private var released = false

    /**
     * Starts [intent] on the pane's display.
     *
     * The display exists only once the pane's surface does, and until then `startActivity`
     * throws `IllegalStateException`. Android 9 says so through an abstract callback class that
     * reflection cannot subclass, so this retries instead.
     * ponytail: polling every 100 ms for 5 s; a compile-only ActivityView stub with the real
     * StateCallback would remove it.
     */
    fun start(intent: Intent, onStarted: () -> Unit = {}, onFailed: (String) -> Unit) {
        val pane = view ?: return onFailed("no ActivityView")
        val startActivity = runCatching {
            pane.javaClass.getMethod("startActivity", Intent::class.java)
        }.getOrElse { return onFailed("no startActivity(Intent)") }
        var attempts = 0
        val attempt = object : Runnable {
            override fun run() {
                if (released) return
                val error = try {
                    startActivity.invoke(pane, intent)
                    AppLogger.i(TAG, "map pane: started after $attempts retries")
                    onStarted()
                    return
                } catch (e: InvocationTargetException) {
                    e.targetException
                } catch (e: ReflectiveOperationException) {
                    e
                }
                if (error is IllegalStateException && ++attempts < READY_ATTEMPTS) {
                    main.postDelayed(this, READY_POLL_MS)
                    return
                }
                AppLogger.w(TAG, "map pane: launch refused: ${error.javaClass.simpleName}: ${error.message}")
                onFailed(error.javaClass.simpleName)
            }
        }
        main.post(attempt)
    }

    /**
     * Calls [onBlack] once the pane has shown nothing but black for [limitMs], with the reason.
     *
     * The 2026-10-21 drive: the pane stayed black until the driver asked for the full-screen map.
     * Whatever the cause, a black two thirds of the screen at departure is worse than the old
     * full-screen handoff, so this hands the map back on its own. It reads a small copy of the
     * pane's own surface, the one the map draws into, so it sees what the driver sees.
     */
    fun watchBlack(limitMs: Long, onBlack: (String) -> Unit) {
        val surface = (view as? ViewGroup)?.getChildAt(0) as? SurfaceView ?: return
        val startedAt = SystemClock.elapsedRealtime()
        val probe = object : Runnable {
            override fun run() {
                if (released) return
                val bitmap = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
                val sample = this
                runCatching {
                    PixelCopy.request(surface, bitmap, { result ->
                        if (released) return@request
                        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
                        bitmap.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
                        val black = when (result) {
                            PixelCopy.SUCCESS -> isBlack(pixels)
                            PixelCopy.ERROR_SOURCE_NO_DATA -> true
                            else -> null
                        }
                        val ms = SystemClock.elapsedRealtime() - startedAt
                        when {
                            black == false -> AppLogger.i(TAG, "map pane: first map frame after $ms ms")
                            black == null -> AppLogger.w(TAG, "map pane: pixel copy refused ($result), not watched")
                            ms >= limitMs -> onBlack(if (result == PixelCopy.SUCCESS) "black" else "no frame")
                            else -> main.postDelayed(sample, BLACK_POLL_MS)
                        }
                    }, main)
                }.onFailure { AppLogger.w(TAG, "map pane: pixel copy failed: ${it.javaClass.simpleName}") }
            }
        }
        main.postDelayed(probe, BLACK_POLL_MS)
    }

    /** Removes the virtual display. Android 9 destroys whatever is still on it. */
    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        val pane = view ?: return
        runCatching { pane.javaClass.getMethod("release").invoke(pane) }
            .onFailure { AppLogger.w(TAG, "map pane: release failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "EVChargePilot"
        private const val ACTIVITY_VIEW = "android.app.ActivityView"
        const val SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"
        private const val READY_POLL_MS = 100L
        private const val READY_ATTEMPTS = 50
        private const val BLACK_POLL_MS = 1_000L
        private const val SAMPLE_W = 48
        private const val SAMPLE_H = 18

        /** Darker than this on every channel is black; a night map still has lit roads and labels. */
        private const val BLACK_CHANNEL = 16

        /** Every sampled pixel black, read from the packed ARGB ints without android.graphics. */
        internal fun isBlack(pixels: IntArray): Boolean = pixels.all {
            (it shr 16 and 0xFF) < BLACK_CHANNEL && (it shr 8 and 0xFF) < BLACK_CHANNEL && (it and 0xFF) < BLACK_CHANNEL
        }

        /** Whether this head unit can host the map at all. Cheap: no binder, no view. */
        fun possible(context: Context): Boolean {
            val pm = context.packageManager
            return runCatching { Class.forName(ACTIVITY_VIEW) }.isSuccess &&
                pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS) &&
                pm.checkPermission(SYSTEM_WINDOW, context.packageName) ==
                PackageManager.PERMISSION_GRANTED &&
                MapApps.comeForward(context) != null
        }
    }
}
