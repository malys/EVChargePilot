package com.evsuite.chargepilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evsuite.hardware.AppLogger

/**
 * Restores automatic trip monitoring after a head-unit start, so recording is automatic in fact.
 *
 * Three things a reader of this file cannot work out from it, and nothing else — the measurement
 * that motivated it and the cost it carries are in CP-080:
 *
 * - `exported` is true because a manifest receiver is not handed a system broadcast otherwise,
 *   and it is safe here for the reason the steering-wheel receivers in this suite are not:
 *   `BOOT_COMPLETED` is a protected broadcast, which only the platform may send.
 * - The action is checked regardless, because a receiver that acts on whatever arrives is a
 *   receiver that trusted its manifest.
 * - Failures are caught because a crash in a boot receiver is the kind of instability this suite
 *   may not introduce on a head unit, and the service is legitimately unreachable on a device
 *   still unlocking.
 */
class TripMonitorBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TripRecordingService.isAutomaticDetectionEnabled(context)) {
            AppLogger.i("EVChargePilot", "boot: automatic trip detection is off, nothing started")
            return
        }
        runCatching { TripRecordingService.monitorAutomaticTrips(context) }
            .onFailure { AppLogger.w("EVChargePilot", "boot: monitor failed: ${it.message}") }
    }
}
