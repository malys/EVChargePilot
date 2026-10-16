package com.evsuite.chargepilot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.view.Display
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import java.util.ArrayDeque

/**
 * CP-094. Can the car's map share one screen with the companion, and if not, can its guidance?
 *
 * Read-only on every line: nothing is embedded, started or sent. It sits in `main` rather than
 * the unstable probes because the owner installs stable builds only, and a probe that never
 * reaches the car answers nothing.
 *
 * **Why displays are watched from process start.** An embedded activity lives on a virtual
 * display its host creates while the embed is on screen. The vendor launcher's map card is up
 * when EVChargePilot is not, so asking at export time would only ever see the panel; the
 * listener keeps every display that appeared while the trip monitor held the process alive.
 *
 * The hidden members are read by reflection and every read is wrapped: a firmware that blocks
 * one reports `unreadable` or `false` for that line, never a crash.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
object SplitScreenProbe {

    enum class Verdict { MAP_EMBEDDABLE, GUIDANCE_ONLY, NEITHER }

    /** The three facts the decision rests on, kept apart from Android so a JVM test covers it. */
    internal fun verdict(
        canEmbed: Boolean,
        navAllowsEmbedding: Boolean,
        guidanceAnswers: Boolean,
    ): Verdict = when {
        canEmbed && navAllowsEmbedding -> Verdict.MAP_EMBEDDABLE
        guidanceAnswers -> Verdict.GUIDANCE_ONLY
        else -> Verdict.NEITHER
    }

    private const val EMBEDDING = "android.permission.ACTIVITY_EMBEDDING"

    /** `ActivityInfo.FLAG_ALLOW_EMBEDDED`, hidden on API 28; read by reflection when present. */
    private const val FLAG_ALLOW_EMBEDDED_FALLBACK = 0x80000000.toInt()

    private const val MAX_SEEN = 16

    /** Guarded by itself: written on the main looper, read on the diagnostics worker. */
    private val seen = ArrayDeque<String>()

    @Volatile
    private var watching = false

    /** Called once from the application. Registration only; each callback is one line. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        SaicNavGuidance.connect(app)
        val displays = app.getSystemService(DisplayManager::class.java) ?: return
        runCatching {
            displays.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    val line = displays.getDisplay(displayId)?.let(::describe) ?: "id=$displayId gone"
                    synchronized(seen) {
                        if (seen.size >= MAX_SEEN) seen.removeFirst()
                        seen.addLast("${System.currentTimeMillis()} $line")
                    }
                }

                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) = Unit
            }, null)
        }
    }

    /** The `[split_screen]` section. Binder reads: call off the main thread. */
    fun report(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val lines = mutableListOf<String>()

        val activityView = runCatching { Class.forName("android.app.ActivityView") }.isSuccess
        val platformSigned =
            pm.checkSignatures("android", context.packageName) == PackageManager.SIGNATURE_MATCH
        lines += "firmware=${FirmwareInfo.getGeneration().name}"
        lines += "activity_view_class=$activityView"
        lines += "platform_signed=$platformSigned"
        // Not declared on purpose: the probe must not widen the manifest. A platform-signed build
        // would be granted it the day it is declared.
        lines += "embedding_permission_declared=false"
        lines += "multi_window_config=" + listOf(
            "config_supportsMultiWindow",
            "config_supportsSplitScreenMultiWindow",
            "config_supportsFreeformWindowManagement",
        ).joinToString(";") { "$it:${frameworkBool(it)}" }

        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        ).map { it.activityInfo.packageName }.distinct().forEach { home ->
            lines += "home=$home activity_embedding=${
                pm.checkPermission(EMBEDDING, home) == PackageManager.PERMISSION_GRANTED
            }"
        }

        val nav = MapApps.installedPackage(context)
        lines += "nav_package=${nav ?: "none"}"
        val embeddable = nav?.let { navActivities(pm, it, lines) }.orEmpty()
        lines += "nav_embeddable=${embeddable.ifEmpty { listOf("none") }.joinToString(",")}"

        context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
            .forEach { lines += "display_now=${describe(it)}" }
        synchronized(seen) { seen.toList() }.ifEmpty { listOf("none since process start") }
            .forEach { lines += "display_seen=$it" }

        val navigating = SaicNavGuidance.isMapNavigating()
        val guidance = SaicNavGuidance.readNow()
        lines += "guidance_bound=${SaicNavGuidance.isAvailable} map_navigating=$navigating"
        lines += "guidance_now status=${guidance.guideStatus} remaining_raw=${guidance.remainingDistanceRaw} " +
            "minutes=${guidance.remainingMinutes} road_present=${guidance.road != null}"
        lines += "guidance_listener=${SaicNavGuidance.isListening} events=${guidance.events} " +
            "next_turn_raw=${guidance.nextTurnDistanceRaw} icon=${guidance.nextTurnIcon} " +
            "direction_present=${guidance.direction != null}"

        val guidanceAnswers = guidance.remainingDistanceRaw != null || guidance.road != null
        lines += "split_screen_verdict=" +
            verdict(activityView && platformSigned, embeddable.isNotEmpty(), guidanceAnswers).name
        lines
    }.getOrElse { listOf("unavailable: ${it.javaClass.simpleName}") }

    /** Lists the map's activities that allow embedding, and records its size and main resize mode. */
    private fun navActivities(pm: PackageManager, pkg: String, lines: MutableList<String>): List<String> {
        val activities = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull().orEmpty()
        lines += "nav_activities=${activities.size}"
        val flag = runCatching {
            ActivityInfo::class.java.getField("FLAG_ALLOW_EMBEDDED").getInt(null)
        }.getOrDefault(FLAG_ALLOW_EMBEDDED_FALLBACK)
        val launch = pm.getLaunchIntentForPackage(pkg)?.component?.className
        activities.firstOrNull { it.name == launch }?.let {
            lines += "nav_main=${it.name.removePrefix(pkg)} resize_mode=${resizeMode(it)} exported=${it.exported}"
        }
        return activities.filter { (it.flags and flag) != 0 }.map { it.name.removePrefix(pkg) }
    }

    private fun resizeMode(info: ActivityInfo): String = runCatching {
        ActivityInfo::class.java.getField("resizeMode").getInt(info).toString()
    }.getOrDefault("unreadable")

    private fun frameworkBool(name: String): String = runCatching {
        val system = Resources.getSystem()
        val id = system.getIdentifier(name, "bool", "android")
        if (id == 0) "absent" else system.getBoolean(id).toString()
    }.getOrDefault("unreadable")

    /** Id, name, size, flags and — hidden on API 28 — the package that owns a virtual display. */
    private fun describe(display: Display): String {
        val owner = runCatching {
            Display::class.java.getMethod("getOwnerPackageName").invoke(display) as String?
        }.getOrNull()
        return "id=${display.displayId} name=${display.name} " +
            "size=${display.mode.physicalWidth}x${display.mode.physicalHeight} " +
            "flags=0x${Integer.toHexString(display.flags)} owner=${owner ?: "-"}"
    }
}
