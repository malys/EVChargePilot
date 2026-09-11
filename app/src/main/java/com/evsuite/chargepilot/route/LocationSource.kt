package com.evsuite.chargepilot.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Where the car is, for a route origin — the one thing the head unit will not tell us.
 *
 * `IGeneralService.getLocationProvider` (tx 6) looks like a position source and is not: it
 * answers with the *name* of the provider the head unit uses. There is no OEM path to a fix, so
 * the origin comes from Android's own location, which CP-043 declared and this reads.
 *
 * Two things from CP-043 that this file is where they stop being documentation:
 *
 * - **Coarse is not enough and coarse may be what the driver granted.** Android 12+ refuses a
 *   fine-only declaration, so `ACCESS_COARSE_LOCATION` is in the manifest and the system will
 *   offer the driver "approximate". An approximate position is not a route origin and carries
 *   no altitude, so [hasPrecise] asks only about the fine permission and a coarse-only grant is
 *   a refusal of what was asked for.
 * - **The prompt may never appear.** `CAR_SPEED` is in permission group `LOCATION` and the
 *   dashboard requests it at startup; on API 28 the system grants a permission from an already
 *   held group without a dialog. Unconfirmed on the vehicle, and the reason the screen has to
 *   tell the driver what it is about to use rather than trusting the platform to ask.
 *
 * Read on demand, never subscribed: this app must not be why the head unit's GPS stays hot. The
 * last known fix is what a route origin needs, and a fix older than [MAX_AGE_MS] is refused
 * rather than used, because a stale origin routes from where the car was.
 *
 * **A cache nobody fills is empty.** The 2026-09-07 session recorded the consequence fifteen
 * times over: the driver granted fine location, and every route then refused with "no fix within
 * 120s". EVTasker's `CarLocation` had already written down why — *"`getLastKnownLocation` is
 * whatever some other app happened to leave behind, which on a head unit with no other GPS
 * client is nothing at all"*. So [requestCurrent] asks the GPS itself, once, for as long as
 * [FIX_TIMEOUT_MS] and no longer, and only when a driver has asked for a route. That is still
 * "on demand": the subscription is removed on the first fix or on the timeout, whichever comes
 * first, and nothing here holds the receiver on between routes.
 */
object LocationSource {

    /** Older than this and it is a place the car has left, not a place it is. */
    const val MAX_AGE_MS = 2 * 60 * 1000L

    /**
     * How long [requestCurrent] keeps the GPS on before giving up.
     *
     * A driver who has just typed a destination is waiting at the kerb and will wait a few
     * seconds; a cold receiver under a carport may need most of this. Longer than this is a
     * screen that looks broken, so the refusal is said out loud instead.
     */
    const val FIX_TIMEOUT_MS = 20 * 1000L

    data class Fix(
        val longitude: Double,
        val latitude: Double,
        val altitudeMetres: Double?,
        val ageMs: Long,
    )

    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The freshest usable fix, or null.
     *
     * `checkSelfPermission` here rather than a cached flag: a grant can be revoked between two
     * screens and a boolean read at startup cannot see that.
     */
    // Lint cannot follow the guard through [hasPrecise]. The guard is the line below, and the
    // SecurityException lint asks about is caught: a revoked grant yields null, never a crash.
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context, nowMs: Long = System.currentTimeMillis()): Fix? {
        if (!hasPrecise(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val best = PROVIDERS.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        val age = nowMs - best.time
        if (age < 0 || age > MAX_AGE_MS) return null
        return fix(best, nowMs)
    }

    /**
     * One fix from the receiver itself, then the subscription is dropped.
     *
     * Copied from EVTasker's `CarLocation.requestCurrent`, which is the version that works on
     * this head unit. [callback] runs on the main thread exactly once: with the first fix that
     * arrives, or with whatever the cache holds by [timeoutMs], or with null.
     */
    // Same guard as [lastKnown]: lint cannot follow it through [hasPrecise].
    @SuppressLint("MissingPermission")
    fun requestCurrent(
        context: Context,
        timeoutMs: Long = FIX_TIMEOUT_MS,
        callback: (Fix?) -> Unit,
    ) {
        if (!hasPrecise(context)) return callback(null)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return callback(null)
        val main = Handler(Looper.getMainLooper())
        var delivered = false
        // Which providers are still worth waiting for. GPS and network are subscribed together
        // and either may go down on its own; giving up on the first one to do so abandoned a
        // GPS fix that was still coming because the head unit has no network provider to speak of.
        val waitingOn = PROVIDERS.toMutableSet()
        lateinit var listener: LocationListener
        fun finish(location: Location?) {
            if (delivered) return
            delivered = true
            runCatching { manager.removeUpdates(listener) }
            callback(location?.let { fix(it, System.currentTimeMillis()) } ?: lastKnown(context))
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)

            // Deprecated on new platforms, abstract on API 28: omitting them does not compile.
            @Deprecated("Required by the API 28 interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                waitingOn.remove(provider)
                if (waitingOn.isEmpty()) finish(null)
            }
        }
        val enabled = PROVIDERS.filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (enabled.isEmpty()) return finish(null)
        waitingOn.retainAll(enabled.toSet())
        runCatching {
            enabled.forEach { provider ->
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }.onFailure { return finish(null) }
        main.postDelayed({ finish(null) }, timeoutMs)
    }

    private fun fix(location: Location, nowMs: Long): Fix = Fix(
        longitude = location.longitude,
        latitude = location.latitude,
        // hasAltitude is false on a fix that carries none; 0.0 would read as sea level.
        altitudeMetres = if (location.hasAltitude()) location.altitude else null,
        ageMs = (nowMs - location.time).coerceAtLeast(0L),
    )

    private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
}
