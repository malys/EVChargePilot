package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        val carrying = listOf(geoUri, "google.navigation:q=$latitude,$longitude")
            .map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .filter { it.resolveActivity(context.packageManager) != null }
        for (intent in carrying) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return Outcome.SENT
        }
        // MAIN/HOME rather than a bare component: it is what the vendor launcher sends, and the
        // navigation app treats it as "come to the foreground" instead of starting a second copy
        // of itself on top of a running guidance session.
        val component = installedComponent(context) ?: return Outcome.NONE
        val toForeground = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(toForeground) }.isSuccess) {
            Outcome.MAP_ONLY
        } else {
            Outcome.NONE
        }
    }

    /** The map package this car actually has, for the Q9 probe line. A package name, never a place. */
    fun installedPackage(context: Context): String? = installedComponent(context)?.packageName

    private fun installedComponent(context: Context): ComponentName? =
        COMPONENTS.firstNotNullOfOrNull { (pkg, cls) ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                ComponentName(pkg, cls)
            }.getOrNull()
        }
}
