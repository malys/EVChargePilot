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

    enum class Verdict { MAP_EMBEDDABLE, GUIDANCE_ONLY, UNPROVEN, NEITHER }

    /**
     * The ways the map or its guidance could reach the companion, each answered on its own.
     *
     * Android 9 lets an activity onto a virtual display another app owns only if it is flagged
     * `allowEmbedded` and the host holds ACTIVITY_EMBEDDING, unless the host holds
     * INTERNAL_SYSTEM_WINDOW, or runs as the system uid, which skip both checks. Both permissions
     * are signature-level: a platform-signed build is granted them the day it declares them, which
     * this probe does not do. None of it works without secondary-display activities at all.
     */
    enum class Option { MAP_SYSTEM_WINDOW, MAP_ALLOWED_EMBEDDING, SPLIT_SCREEN, GUIDANCE }

    /** What the options rest on, kept apart from Android so a JVM test covers it. */
    internal data class Facts(
        val activityView: Boolean,
        val secondaryDisplays: Boolean,
        val platformSigned: Boolean,
        val systemUid: Boolean,
        val navAllowsEmbedding: Boolean,
        val splitScreenConfig: Boolean,
        val navResizeable: Boolean,
        val guidanceBound: Boolean,
        val navigating: Boolean,
        val guidanceAnswers: Boolean,
    )

    internal fun options(f: Facts): Map<Option, String> {
        val host = when {
            !f.activityView -> "no: no ActivityView on this firmware"
            !f.secondaryDisplays -> "no: activities on secondary displays are off"
            else -> null
        }
        return linkedMapOf(
            Option.MAP_SYSTEM_WINDOW to (host ?: when {
                f.systemUid -> "possible: runs as the system uid"
                f.platformSigned -> "possible_if_declared: INTERNAL_SYSTEM_WINDOW"
                else -> "no: not platform signed"
            }),
            Option.MAP_ALLOWED_EMBEDDING to (host ?: when {
                !f.navAllowsEmbedding -> "no: no map activity allows embedding"
                f.systemUid || f.platformSigned -> "possible_if_declared: ACTIVITY_EMBEDDING"
                else -> "no: not platform signed"
            }),
            Option.SPLIT_SCREEN to when {
                !f.splitScreenConfig -> "no: split screen is off in the framework"
                !f.navResizeable -> "no: the map is not resizeable"
                else -> "plausible: the map would have to be launched docked, unproven"
            },
            Option.GUIDANCE to when {
                !f.guidanceBound -> "no: the guidance service is not bound"
                f.navigating && f.guidanceAnswers -> "proven"
                else -> "unproven: start a route in the car's navigation, then export"
            },
        )
    }

    internal fun verdict(options: Map<Option, String>): Verdict = when {
        listOf(Option.MAP_SYSTEM_WINDOW, Option.MAP_ALLOWED_EMBEDDING)
            .any { options[it]?.startsWith("possible") == true } -> Verdict.MAP_EMBEDDABLE
        options[Option.GUIDANCE] == "proven" -> Verdict.GUIDANCE_ONLY
        options[Option.GUIDANCE]?.startsWith("unproven") == true -> Verdict.UNPROVEN
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
        val secondaryDisplays =
            pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
        val uid = android.os.Process.myUid()
        lines += "firmware=${FirmwareInfo.getGeneration().name}"
        lines += "activity_view_class=$activityView"
        lines += "platform_signed=$platformSigned"
        lines += "secondary_display_activities=$secondaryDisplays"
        lines += "process_uid=$uid"
        // CP-094 declares it for the companion overlay; granted only where the build is platform-signed.
        lines += "embedding_permission_granted=" + (
            pm.checkPermission("android.permission.INTERNAL_SYSTEM_WINDOW", context.packageName) ==
                PackageManager.PERMISSION_GRANTED
        )
        lines += "map_companion_possible=${MapCompanionOverlay.possible(context)}"
        // CP-094: what covered the split screen. Unreadable means REAL_GET_TASKS was not granted.
        lines += "tasks_now=${MapApps.recentTasks(context)?.joinToString(" < ") ?: "unreadable"}"
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
        var mainResizeMode: String? = null
        val embeddable = nav?.let { navActivities(pm, it, lines) { mode -> mainResizeMode = mode } }.orEmpty()
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

        // A zero remaining distance with no route is the service's idle answer, not guidance.
        val guidanceAnswers = (guidance.remainingDistanceRaw ?: 0) > 0 || guidance.road != null
        val options = options(
            Facts(
                activityView = activityView,
                secondaryDisplays = secondaryDisplays,
                platformSigned = platformSigned,
                systemUid = uid == android.os.Process.SYSTEM_UID,
                navAllowsEmbedding = embeddable.isNotEmpty(),
                splitScreenConfig = frameworkBool("config_supportsSplitScreenMultiWindow") == "true",
                // 0 is RESIZE_MODE_UNRESIZEABLE; unreadable proves nothing either way.
                navResizeable = mainResizeMode.let { it != null && it != "0" && it != "unreadable" },
                guidanceBound = SaicNavGuidance.isAvailable,
                navigating = navigating == true,
                guidanceAnswers = guidanceAnswers,
            )
        )
        options.forEach { (option, answer) -> lines += "option.${option.name.lowercase()}=$answer" }
        lines += "split_screen_verdict=" + verdict(options).name
        // The verdict and options are the 2026-10-18 discovery. What ships since 2026-10-25 is
        // neither pane nor docked map: the companion window over the full-screen map, see Q12.
        lines += "split_screen_shipped=overlay"
        lines
    }.getOrElse { listOf("unavailable: ${it.javaClass.simpleName}") }

    /** Lists the map's activities that allow embedding, and records its size and main resize mode. */
    private fun navActivities(
        pm: PackageManager,
        pkg: String,
        lines: MutableList<String>,
        mainResizeMode: (String) -> Unit,
    ): List<String> {
        val activities = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        }.getOrNull().orEmpty()
        lines += "nav_activities=${activities.size}"
        val flag = runCatching {
            ActivityInfo::class.java.getField("FLAG_ALLOW_EMBEDDED").getInt(null)
        }.getOrDefault(FLAG_ALLOW_EMBEDDED_FALLBACK)
        val launch = pm.getLaunchIntentForPackage(pkg)?.component?.className
        activities.firstOrNull { it.name == launch }?.let {
            val mode = resizeMode(it).also(mainResizeMode)
            lines += "nav_main=${it.name.removePrefix(pkg)} resize_mode=$mode exported=${it.exported}"
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
