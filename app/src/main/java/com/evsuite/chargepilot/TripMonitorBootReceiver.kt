package com.evsuite.chargepilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evsuite.hardware.AppLogger

/**
 * Restores automatic trip monitoring after a head-unit start, so recording is automatic in fact.
 *
 * [TripRecordingService] already outlives the dashboard — it is a *started* foreground service,
 * which is the whole reason a trip survives the driver switching to the media app. What it does
 * not survive is the car being switched off: the head unit shuts the process down with
 * everything else, and until CP-074 nothing started it again. The monitor therefore came back
 * only when somebody opened the app, and a drive begun without opening it was a drive nobody
 * recorded.
 *
 * CP-080. Measured, which is why this exists: the odometer moved 148 km between the 2026-09-17 and
 * 2026-09-24 bundles while the trip history recorded 32 km of it. Every threshold downstream is
 * paid for in recorded kilometres — the consumption fit needs a span of speeds, the health
 * estimate needs complete discharges the app watched, and eco-driving needs the drive it is
 * scoring — so coverage is upstream of all of them.
 *
 * **What it costs is bounded by the service, not by a promise here.** With automatic detection
 * off, nothing starts. With it on, the service samples at 1 Hz and suspends itself after ten
 * consecutive reads with no usable speed, which is what a head unit that booted without the
 * vehicle interfaces looks like. It writes to disk only when a completed trip is stored.
 *
 * `exported` is true because a manifest receiver does not hear a system broadcast otherwise,
 * and it is safe here for the reason the steering-wheel receiver elsewhere in this suite is
 * not: `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are protected broadcasts, which only the
 * platform may send. The action is checked anyway — a receiver that acts on whatever arrives is
 * a receiver that trusted its manifest.
 */
class TripMonitorBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!TripRecordingService.isAutomaticDetectionEnabled(context)) {
            AppLogger.i(TAG, "boot: automatic trip detection is off, nothing started")
            return
        }
        // A failure here must not take the platform's boot broadcast down with it: the service
        // is unavailable on a device still unlocking, and a crash in a boot receiver is the
        // kind of instability this app is not permitted to introduce on a head unit.
        runCatching { TripRecordingService.monitorAutomaticTrips(context) }
            .onSuccess { AppLogger.i(TAG, "boot: automatic trip monitor started; action=$action") }
            .onFailure { AppLogger.w(TAG, "boot: monitor could not start: ${it.message}") }
    }

    private companion object { const val TAG = "EVChargePilot" }
}
