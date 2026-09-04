package com.evsuite.chargepilot.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
 */
object LocationSource {

    /** Older than this and it is a place the car has left, not a place it is. */
    const val MAX_AGE_MS = 2 * 60 * 1000L

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
        return Fix(
            longitude = best.longitude,
            latitude = best.latitude,
            // hasAltitude is false on a fix that carries none; 0.0 would read as sea level.
            altitudeMetres = if (best.hasAltitude()) best.altitude else null,
            ageMs = age,
        )
    }

    private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
}
