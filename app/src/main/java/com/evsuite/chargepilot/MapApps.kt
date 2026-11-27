package com.evsuite.chargepilot

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Reaching the head unit's map, with the destination if the car will take it and without it
 * otherwise.
 *
 * CP-056's Q9 recorded the bad half of this on the road: nothing on this car declares a `geo:`
 * intent filter, so `resolveActivity` answers null and `startActivity` had nothing to reach. The
 * button said "nothing accepted a destination" and stopped there.
 *
 * EVTasker's `NAVIGATE_TO` works on the same car, and this is why: after the URI intents it sends
 * an explicit component, taken from what the vendor's own launcher and navigation widget do —
 * they hard-code component names per market rather than resolve an intent, which is the platform
 * saying resolution is not available here. **The destination is lost on that path.** MG4
 * Navigator opens at its default view and the driver types the place in. That is worth more than
 * a button that does nothing, and it is worth strictly less than a handoff, so the two outcomes
 * are reported separately and the screen says which one happened.
 */
object MapApps {

    /** What the tap achieved. Never collapse [MAP_ONLY] into [SENT]: the driver must type. */
    enum class Outcome { SENT, MAP_ONLY, NONE }

    /**
     * Navigation apps shipped on SAIC head units, in the order the vendor's launcher prefers
     * them. Kept identical to EVTasker's list, which is the one proven on this car.
     */
    private val COMPONENTS = listOf(
        // EU/overseas MG4 — the one this project targets.
        "com.saicmotor.navigation" to "com.saicmotor.navigation.MainActivity",
        "com.telenav.app.arp" to "com.telenav.arp.module.map.MainActivity",
        "com.nng.igo.primong" to "com.navngo.igo.javaclient.MainActivity",
        "com.mmi.navimaps_auto" to "hr.mireo.arthur.common.App",
    )

    /**
     * Sends [geoUri], and falls back to opening the map where it is.
     *
     * [geoUri] comes from `NavigationHandoff.geoUri`, so the coordinate formatting and the label
     * sanitising stay in the one place a JVM test proves them.
     */
    fun open(context: Context, geoUri: String, latitude: Double, longitude: Double): Outcome {
        // MG4Link declares geo: and google.navigation: and drops the destination: Android Auto
        // has no way to hand one to the phone. Reaching it would read "sent" for nothing.
        val carrying = listOf(geoUri, "google.navigation:q=$latitude,$longitude")
            .map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .filter { takesDestination(it.resolveActivity(context.packageManager)?.packageName) }
        for (intent in carrying) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return Outcome.SENT
        }
        val component = installedComponent(context) ?: return Outcome.NONE
        // The same URI, but addressed rather than resolved. A missing intent filter is what
        // stopped the implicit intents, and an explicit component skips filter matching
        // entirely — the activity still has to read the data, which is exactly what is unknown.
        // So it is attempted, and it is still reported as MAP_ONLY: this build cannot see
        // whether the map took the destination, and a driver reading "sent" would stop looking.
        val addressed = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(addressed) }.isSuccess) return Outcome.MAP_ONLY
        return if (toForeground(context)) Outcome.MAP_ONLY else Outcome.NONE
    }

    /**
     * Brings the installed map to the front, carrying nothing.
     *
     * For the destination the adapter has already taken: `startNavFromEVRout` and `goTo` hand the
     * route over on the binder and raise no window, so the drive of 2026-09-10 came back with the
     * route accepted, guidance running at forty seconds and MG4 Navigator never on screen. This
     * is the one call that puts it there.
     *
     * MAIN/HOME rather than a bare component: it is what the vendor launcher sends, and the
     * navigation app treats it as "come to the foreground" instead of starting a second copy of
     * itself on top of a running guidance session.
     */
    fun toForeground(context: Context): Boolean {
        val intent = comeForward(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * MG4Link's own entry, or null when it is not installed. It installs as an update of AllGo's
     * Android Auto and keeps that package, so the class tells it from the car's original app.
     * With a phone connected it brings the projection to the front; without one, its home screen.
     */
    fun mg4Link(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(MG4LINK_PACKAGE)
            ?.takeIf { isMg4Link(it.component?.className) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * MG4Link when the driver chose the Android Auto split, it is installed and a phone is linked
     * over Bluetooth; null otherwise. With the phone off MG4Link can only show its home screen,
     * so the split and Navigate fall back to MG4 Navigator (2026-11-27). MG4Link publishes its
     * session state to no other app, so a linked phone is the proxy: linked but not projecting
     * still opens MG4Link.
     */
    fun androidAutoMap(context: Context): Intent? =
        if (ChargeStopHandoff.androidAutoSplit(context) && phoneLinked(context)) mg4Link(context) else null

    /** A hands-free or audio Bluetooth link is up. Unreadable is never yes. */
    // BLUETOOTH is install-time; a SecurityException anyway lands in runCatching as "no".
    @Suppress("MissingPermission")
    private fun phoneLinked(context: Context): Boolean = runCatching {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        adapter.isEnabled && listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).any {
            adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED
        }
    }.getOrDefault(false)

    /** The map the split goes beside: [androidAutoMap], MG4 Navigator otherwise. */
    fun splitMap(context: Context): Intent? = androidAutoMap(context) ?: comeForward(context)

    /** Brings [splitMap] to the front. */
    fun splitToForeground(context: Context): Boolean {
        val intent = splitMap(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** The intent [toForeground] sends, and CP-094's map companion reads its package from. */
    fun comeForward(context: Context): Intent? {
        val component = installedComponent(context) ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The map package this car actually has, for the Q9 probe line. A package name, never a place. */
    fun installedPackage(context: Context): String? = installedComponent(context)?.packageName

    /**
     * The map's own exported activities, for the Q9 probe line.
     *
     * Two drives have now proved that no destination reaches this car through a published intent
     * filter, and guessing a vendor-specific action from a laptop is how a build ships a button
     * that does nothing. An exported activity is the only thing another app can start, so their
     * names are the list of everything that could ever be tried — and a name like
     * `SearchResultActivity` is a lead where `MainActivity` is not.
     *
     * Class names only. Nothing here is a coordinate, a place or a key.
     */
    fun entryPoints(context: Context): List<String> {
        val pkg = installedPackage(context) ?: return emptyList()
        val info = runCatching {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        }.getOrNull() ?: return emptyList()
        return info.activities.orEmpty()
            .filter { it.exported }
            .map { it.name.removePrefix(pkg) }
            .take(MAX_ENTRY_POINTS)
    }

    /**
     * CP-094: the top activity of each recent task, the focused one first, or null when the
     * platform would answer only this app's own tasks.
     *
     * `REAL_GET_TASKS` is signature|privileged, granted to this platform-signed build, and
     * read-only: names, never a way to move a task. Without it `getRunningTasks` answers this
     * app and the launcher, which looks like an answer and is not one, so that case is null.
     */
    @Suppress("DEPRECATION")
    fun recentTasks(context: Context): List<String>? {
        if (context.checkSelfPermission(REAL_GET_TASKS) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { manager.getRunningTasks(MAX_TASKS) }.getOrNull()
            ?.mapNotNull { it.topActivity?.flattenToShortString() }
    }

    /** Whether the focused task is the map's, from [recentTasks]. Unreadable is never yes. */
    internal fun mapOnTop(tasks: List<String>?, mapPackage: String?): Boolean =
        mapPackage != null && tasks?.firstOrNull()?.substringBefore('/') == mapPackage

    /** Whether the app a geo: intent resolves to does anything with the place. */
    internal fun takesDestination(resolvedPackage: String?): Boolean =
        resolvedPackage != null && resolvedPackage != MG4LINK_PACKAGE

    /** MG4Link, and not the car's original AllGo app it replaces under the same package. */
    internal fun isMg4Link(launchClass: String?): Boolean =
        launchClass?.startsWith(MG4LINK_CLASS_PREFIX) == true

    private const val REAL_GET_TASKS = "android.permission.REAL_GET_TASKS"
    internal const val MG4LINK_PACKAGE = "com.allgo.app.androidauto"
    private const val MG4LINK_CLASS_PREFIX = "com.andrerinas.openheadunit."
    private const val MAX_TASKS = 4

    /** A probe line is read on a laptop, not scrolled: enough to spot a lead, not a manifest dump. */
    private const val MAX_ENTRY_POINTS = 24

    private fun installedComponent(context: Context): ComponentName? =
        COMPONENTS.firstNotNullOfOrNull { (pkg, cls) ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                ComponentName(pkg, cls)
            }.getOrNull()
        }
}
