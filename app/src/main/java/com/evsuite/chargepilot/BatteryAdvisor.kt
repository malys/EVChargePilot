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
import com.evsuite.hardware.telemetry.BatteryDigest
import com.evsuite.hardware.telemetry.BatteryDigestStore
import com.evsuite.hardware.telemetry.CalibrationVerdict
import com.evsuite.hardware.telemetry.DigestChange
import com.evsuite.hardware.telemetry.EnergySnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The battery companion's daily round and its one way of speaking up unasked (CP-087).
 *
 * **Once a day.** At the first sample of a calendar day the four ledger analyses are recomputed,
 * in both flavors, and the day is written to [BatteryDigestStore]. What moved past its own band
 * since the day before becomes advice. A process that restarts during the day rewrites the day
 * and says nothing again.
 *
 * **Parked only.** Advice goes to the [AdviceJournal] at once; the notification waits for a car
 * that reads stopped, fails closed on an unreadable speed, and posts at most one per kind a day.
 * A moving car gets nothing here: driving advice stays with the voice (CP-083).
 *
 * Runs on the sampler thread, which owns the ledger it reads.
 */
internal class BatteryAdvisor(
    private val context: Context,
    /** The app's own language: a service keeps the head unit's otherwise (CP-077). */
    private val text: () -> Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val filesDir: File = context.filesDir
    private val journal = AdviceJournal.of(filesDir)
    private val digests = BatteryDigestStore(File(filesDir, BatteryDigestStore.FILE_NAME))
    private var digestDay: String? = null
    private var pending = true

    fun onSample(value: EnergySnapshot) {
        val day = Instant.ofEpochMilli(value.timestampMs).atZone(zone).toLocalDate().toString()
        if (day != digestDay) {
            digestDay = day
            runCatching { digest(day, value.timestampMs) }
                .onFailure { AppLogger.w(TAG, "battery digest failed: ${it.message}") }
        }
        if (!pending) return
        val gate = ParkedDeletionPolicy.gate(value.speedKmh, value.timestampMs, System.currentTimeMillis())
        if (gate != ParkedDeletionGate.PARKED) return
        runCatching { flush(value.timestampMs) }
            .onFailure { AppLogger.w(TAG, "advice notification failed: ${it.message}") }
    }

    /** Anything the app says — the trip-end line included — is kept, then notified when parked. */
    fun record(kind: AdviceKind, line: String, atMs: Long) {
        if (journal.append(AdviceEntry(atMs, kind, line))) pending = true
    }

    private fun digest(day: String, nowMs: Long) {
        val settings = VehicleSettings.read(context)
        val learned = LocalBatteryLearning.refresh(filesDir, settings.usableCapacityKwhWhenNew, nowMs)
        val today = BatteryDigest.of(day, learned.health, learned.drift, learned.exposure, learned.charge)
        val alreadyToday = digests.read().lastOrNull()?.day == day
        val previous = digests.upsert(today)
        if (alreadyToday) return
        BatteryDigest.changes(previous, today).forEach { record(AdviceKind.BATTERY, describe(it), nowMs) }
    }

    private fun describe(change: DigestChange): String {
        val res = text()
        return when (change) {
            is DigestChange.HealthMoved -> res.getString(
                R.string.advice_health_moved, change.toPercent, change.bandPercent, change.fromPercent,
            )
            is DigestChange.HealthBandNarrowed -> {
                val from = change.fromBandPercent
                if (from == null) {
                    res.getString(R.string.advice_health_first, change.healthPercent, change.toBandPercent)
                } else {
                    res.getString(
                        R.string.advice_health_narrowed, change.healthPercent, change.toBandPercent, from,
                    )
                }
            }
            is DigestChange.CalibrationChanged -> {
                val state = res.getString(
                    when (change.to) {
                        CalibrationVerdict.SETTLED -> R.string.battery_health_calibration_state_settled
                        CalibrationVerdict.WATCH -> R.string.battery_health_calibration_state_watch
                        CalibrationVerdict.RE_ANCHOR_SUGGESTED -> R.string.battery_health_calibration_state_re_anchor
                    },
                )
                listOfNotNull(
                    res.getString(R.string.advice_calibration, state),
                    change.daysSinceFullCharge?.let {
                        res.getString(R.string.battery_health_calibration_last_full, it)
                    },
                    if (change.to == CalibrationVerdict.RE_ANCHOR_SUGGESTED) {
                        res.getString(R.string.battery_health_calibration_re_anchor)
                    } else null,
                ).joinToString(" ")
            }
        }
    }

    private fun flush(nowMs: Long) {
        pending = false
        if (!notificationsEnabled(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val entries = journal.read()
        val ofToday = entries.filter {
            Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == today
        }
        val waiting = ofToday.filter { !it.notified }
        if (waiting.isEmpty()) return
        val spent = ofToday.filter { it.notified }.map { it.kind }.toSet()
        val posted = waiting.groupBy { it.kind }
            .filterKeys { it !in spent }
            .map { (kind, list) -> post(manager, kind, list.last()); list.last() }
        // What the cap held back is still in the history; it is not queued for tomorrow.
        journal.markNotified(waiting.filter { entry -> posted.any { it.kind == entry.kind } } + posted)
    }

    // The head unit runs API 28, where no permission gates a notification. POST_NOTIFICATIONS is
    // deliberately not declared (see the manifest); on API 33+ flush() finds it missing and posts
    // nothing, and the advice stays in the history.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(manager: NotificationManagerCompat, kind: AdviceKind, entry: AdviceEntry) {
        val res = text()
        createChannel(res)
        val intent = Intent(context, AdviceHistoryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, kind.ordinal, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(res.getString(R.string.advice_notification_title))
            .setContentText(entry.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_BASE_ID + kind.ordinal, notification)
    }

    private fun createChannel(res: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                res.getString(R.string.advice_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = res.getString(R.string.advice_channel_description) },
        )
    }

    companion object {
        private const val TAG = "EVChargePilot"
        private const val CHANNEL_ID = "battery_advice"
        private const val NOTIFICATION_BASE_ID = 100
        private const val PREFERENCES = "battery_advice"
        private const val PREF_NOTIFY = "notifications_enabled"

        fun notificationsEnabled(context: Context): Boolean = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(PREF_NOTIFY, true)

        fun storeNotificationsEnabled(context: Context, enabled: Boolean) = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_NOTIFY, enabled).apply()
    }
}
