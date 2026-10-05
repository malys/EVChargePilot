package com.evsuite.chargepilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.telemetry.EnergySnapshot

/**
 * Tells the driver once that every validation question has an answer, so the bundle is worth
 * exporting now rather than after another drive spent guessing.
 *
 * Parked only, with the same gate as the battery advice: the questions mostly answer
 * themselves on the road, and a notice that pops up at speed is the overlay the suite forbids.
 * Once per process — the rings are in memory, so a restart starts the count again anyway.
 */
internal object ValidationReadyNotice {

    @Volatile
    private var posted = false

    /** On the sampler thread, every sample; one flag and one set comparison until it fires. */
    fun onSample(context: Context, text: () -> Context, value: EnergySnapshot) {
        if (posted || !ValidationProbe.allAnswered()) return
        val gate = ParkedDeletionPolicy.gate(value.speedKmh, value.timestampMs, System.currentTimeMillis())
        if (gate != ParkedDeletionGate.PARKED) return
        posted = true
        AppLogger.i(TAG, "validation: every question answered, export suggested")
        runCatching { post(context.applicationContext, text()) }
            .onFailure { AppLogger.w(TAG, "validation notice failed: ${it.message}") }
    }

    // API 28 head unit: no permission gates a notification. On API 33+ without
    // POST_NOTIFICATIONS (not declared) nothing is posted; the log line above still says it.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(context: Context, res: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                res.getString(R.string.validation_ready_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, DiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = res.getString(R.string.validation_ready_text)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(res.getString(R.string.validation_ready_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private const val TAG = "EVChargePilot"
    private const val CHANNEL_ID = "validation_ready"
    private const val NOTIFICATION_ID = 200
}
