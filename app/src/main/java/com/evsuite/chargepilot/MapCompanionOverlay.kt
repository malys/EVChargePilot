package com.evsuite.chargepilot

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.saic.SaicNavGuidance
import java.util.concurrent.Executors

/**
 * CP-094, owner decision 2026-10-25: MG4 Navigator stays full screen on the main display, and the
 * companion is a window drawn over its right quarter.
 *
 * **Why not the embedded map any more.** Android 9 resumes one activity on the whole head unit.
 * A map embedded on a virtual display was paused whenever the companion held the focus, so it
 * froze or went black (2026-10-19 to 10-24), and handing it back started a second Navigator
 * full screen while the first stayed on the pane: two guidance voices, and a frozen pane behind
 * Back (2026-10-25). A window is not an activity: it never pauses the map, a touch on it takes no
 * focus, and Navigator is never moved, so there is one copy of it and nothing to hand back.
 *
 * **Never over anything but the map.** The window is in place only while Navigator's task is the
 * top one, checked every [POLL_MS]: the reverse camera, the launcher or this app's own screens
 * remove it. shortcut: polling leaves up to [POLL_MS] over a screen that has just come up; the
 * hidden `TaskStackListener` would remove it on the change itself, at the cost of compiling
 * against a framework stub.
 *
 * **Only while guiding** — when the charge-stop handoff opened it. It ends when the car stops
 * guiding, cancelled or arrived, and on its button, so Navigator's own menus and keyboard get
 * their quarter back. Owner decision 2026-10-30: launching the app opens the same split with no
 * route, and that one stays [untilClosed]. Its button opens the app full screen, the app's own
 * split icon comes back here.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
internal object MapCompanionOverlay {

    private const val TAG = "EVChargePilot"
    private const val POLL_MS = 200L
    private const val GUIDANCE_POLL_MS = 2_000L

    /** The handoff raises the map before the car confirms the route: time for it to start. */
    internal const val START_GRACE_MS = 30_000L

    /** One unreadable or false answer is not the end of a route. */
    internal const val LOST_MS = 5_000L

    private const val SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"
    private const val ALL_TASKS = 32

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var binding: ActivityNavSplitBinding? = null
    private var split: SplitCompanion? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var mapPackage: String? = null
    private var shownAt = 0L
    private var lastGuidingAt: Long? = null
    private var guidanceReadAt = 0L
    private var renderedAt = 0L
    private var shows = 0
    private var guidingHeard = false
    private var untilClosed = false
    private var recorder: TripRecordingService? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "chargepilot-overlay") }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recorder = (service as? TripRecordingService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
        }
    }

    /** Granted to the platform-signed build only, and there must be a map to draw beside. */
    fun possible(context: Context): Boolean =
        context.packageManager.checkPermission(SYSTEM_WINDOW, context.packageName) ==
            PackageManager.PERMISSION_GRANTED && MapApps.comeForward(context) != null

    /** Arms the companion for the drive [extras] describes, replacing any earlier one. */
    fun show(context: Context, extras: Intent, untilClosed: Boolean = false) {
        main.post {
            end("replaced")
            this.untilClosed = untilClosed
            val app = context.applicationContext.also { this.app = it }
            val themed = ContextThemeWrapper(companionContext(app), R.style.Theme_EVChargePilot)
            val binding = ActivityNavSplitBinding.inflate(LayoutInflater.from(themed)).also { this.binding = it }
            binding.mapPane.visibility = View.GONE
            binding.root.setBackgroundResource(R.color.ev_background)
            binding.splitFullMap.setOnClickListener {
                end("button")
                // The full app, not the full map: the split is the default, so its one way out
                // goes to what the quarter cannot show.
                runCatching {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
            split = SplitCompanion(themed, binding, extras, preview = false) { recorder }
            params = WindowManager.LayoutParams(
                app.resources.displayMetrics.widthPixels / 4,
                WindowManager.LayoutParams.MATCH_PARENT,
                // Not TYPE_APPLICATION_OVERLAY: Android 9 checks that one against the user's
                // overlay setting. The legacy alert types are what INTERNAL_SYSTEM_WINDOW opens,
                // and TYPE_PHONE is the lowest of them, still above every app.
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE,
            ).apply { gravity = Gravity.END or Gravity.TOP }
            mapPackage = MapApps.comeForward(app)?.component?.packageName
            shownAt = SystemClock.elapsedRealtime()
            lastGuidingAt = null
            shows = 0
            guidingHeard = false
            app.bindService(Intent(app, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
            note("armed, map=$mapPackage, untilClosed=$untilClosed, navigator ${navigatorCopies(app)}")
            main.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val app = app ?: return
            val now = SystemClock.elapsedRealtime()
            if (!untilClosed && guidanceOver(now, shownAt, lastGuidingAt)) return end("guidance over")
            if (now - guidanceReadAt >= GUIDANCE_POLL_MS) {
                guidanceReadAt = now
                // A read still in flight when this arming ends must not land on the next one.
                val session = shownAt
                worker.execute {
                    if (runCatching { SaicNavGuidance.isMapNavigating() }.getOrNull() == true) {
                        val at = SystemClock.elapsedRealtime()
                        main.post {
                            if (this@MapCompanionOverlay.app == null || shownAt != session) return@post
                            lastGuidingAt = at
                            if (!guidingHeard) note("car guiding ${at - shownAt} ms after the tap")
                            guidingHeard = true
                        }
                    }
                }
            }
            val tasks = MapApps.recentTasks(app)
            place(MapApps.mapOnTop(tasks, mapPackage), tasks)
            if (this@MapCompanionOverlay.app == null) return
            recorder?.latest?.takeIf { attached && it.timestampMs != renderedAt }?.let {
                renderedAt = it.timestampMs
                split?.render(it)
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Adds or removes the window; removed, it takes no touch either. */
    private fun place(onMap: Boolean, tasks: List<String>?) {
        if (onMap == attached) return
        val view = binding?.root ?: return
        val manager = app?.getSystemService(WindowManager::class.java) ?: return
        runCatching { if (onMap) manager.addView(view, params) else manager.removeView(view) }
            .onSuccess {
                attached = onMap
                val parked = recorder?.latest?.parked
                if (onMap) {
                    note("shown #${++shows}, parked=$parked, navigator ${navigatorCopies(view.context)}")
                    if (shows == 1) view.post { noteGeometry(view) }
                } else {
                    note("hidden, top=${tasks?.firstOrNull() ?: "unreadable"}, parked=$parked")
                }
            }
            .onFailure {
                // Refused once is refused for the drive: the map stays full screen, as before CP-094.
                note("window refused: ${it.javaClass.simpleName}: ${it.message}")
                end("window refused")
            }
    }

    /** Removes the window and stops watching; the map stays as it is, full screen. */
    private fun end(why: String) {
        val app = app ?: return
        main.removeCallbacks(tick)
        if (attached) {
            runCatching { app.getSystemService(WindowManager::class.java)?.removeView(binding?.root) }
            attached = false
        }
        split?.close()
        split = null
        binding = null
        recorder = null
        runCatching { app.unbindService(connection) }
        note("ended, $why, shown $shows time(s), guiding heard=$guidingHeard, navigator ${navigatorCopies(app)}")
        this.app = null
    }

    /** The app log and Q12 get the same line, so the bundle answers the question on its own. */
    private fun note(line: String) {
        AppLogger.i(TAG, "map companion: $line")
        ValidationProbe.record(ValidationQuestion.MAP_COMPANION) { line }
    }

    /** Where the window landed: the right quarter of the panel, full height, or not. */
    private fun noteGeometry(view: View) {
        if (!view.isAttachedToWindow) return
        val at = IntArray(2).also(view::getLocationOnScreen)
        val metrics = view.resources.displayMetrics
        note("window x=${at[0]} y=${at[1]} ${view.width}x${view.height} on ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    /**
     * The map's tasks and activities. The 2026-10-25 hand-back started a second Navigator; one
     * reads `tasks=1 activities=1`. Read-only, under `REAL_GET_TASKS`.
     */
    @Suppress("DEPRECATION")
    private fun navigatorCopies(context: Context): String {
        val pkg = mapPackage ?: return "unknown"
        val tasks = runCatching {
            context.getSystemService(ActivityManager::class.java)?.getRunningTasks(ALL_TASKS)
        }.getOrNull() ?: return "unreadable"
        val map = tasks.filter { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }
        return "tasks=${map.size} activities=${map.sumOf { it.numActivities }}"
    }

    /** Whether the drive is over: the car stopped guiding, or never started after the handoff. */
    internal fun guidanceOver(now: Long, shownAt: Long, lastGuidingAt: Long?): Boolean =
        if (lastGuidingAt == null) now - shownAt > START_GRACE_MS else now - lastGuidingAt > LOST_MS

    /**
     * The night palette, beside a map dark in both modes (the owner's 2026-10-22 exception to
     * DESIGN.md's DayNight rule), in CP-077's language, which AppCompat applies to activities only.
     */
    private fun companionContext(app: Context): Context {
        val config = Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return app.createConfigurationContext(config)
    }
}
