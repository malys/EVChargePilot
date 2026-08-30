package com.evsuite.chargepilot

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.TripDetector
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the sampler, so a trip survives the driver looking at something else.
 *
 * Recording used to live in the activity: sampling ran between `onStart` and `onStop`, and
 * switching to the media app for ten minutes left a hole in the middle of the drive. The
 * trip accumulator refuses to integrate across a gap longer than five seconds — correctly,
 * since it has no idea what the car did in between — so the trip came back with its distance
 * and energy quietly missing the part where the driver was not watching. A log that only
 * works while you stare at it is not a log.
 *
 * The service samples whenever something needs it: while a dashboard is bound, while a trip
 * is recording, or while automatic detection is enabled. Automatic monitoring keeps only the
 * latest sample in memory; it does not write to disk until a completed trip is stored. When no
 * consumer remains it stops itself, because background reads with no purpose waste resources.
 */
class TripRecordingService : Service() {

    /** Delivered on the main thread, so a listener can render without hopping again. */
    fun interface Listener {
        fun onSample(snapshot: EnergySnapshot)
    }

    inner class LocalBinder : Binder() {
        val service: TripRecordingService get() = this@TripRecordingService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val sampler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chargepilot-sampler")
    }
    private lateinit var reader: EnergyTelemetryReader
    private lateinit var tripStore: EnergyTripHistoryStore
    private val detector = TripDetector()
    private val pendingHistoryWrites = AtomicInteger()
    private val missingSpeedSamples = AtomicInteger()

    private var samplingTask: ScheduledFuture<*>? = null
    @Volatile private var boundClients = 0
    private var listener: Listener? = null
    private var samplesSinceNotification = 0
    @Volatile var automaticDetectionEnabled: Boolean = true
        private set
    @Volatile private var automaticMonitoringSuspended = false

    @Volatile private var latestSnapshot: EnergySnapshot? = null

    /** The last reading, for a client that binds mid-drive and needs something to draw now. */
    val latest: EnergySnapshot? get() = latestSnapshot

    val isRecording: Boolean get() = EnergyTripSession.isRecording

    override fun onCreate() {
        super.onCreate()
        reader = EnergyTelemetryReader(applicationContext)
        tripStore = EnergyTripHistoryStore(File(filesDir, "trips.json"))
        automaticDetectionEnabled = isAutomaticDetectionEnabled(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        ensureSampling()
        return binder
    }

    /** True so a returning dashboard gets [onRebind] rather than a fresh [onBind]. */
    override fun onUnbind(intent: Intent?): Boolean {
        boundClients--
        stopWhenNobodyNeedsIt()
        return true
    }

    override fun onRebind(intent: Intent?) {
        boundClients++
        ensureSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRIP -> startTrip()
            ACTION_MONITOR_AUTOMATIC -> setAutomaticDetectionEnabled(true)
        }
        // Not sticky: a restarted process has no accumulator and no samples, and resuming a
        // trip it cannot account for would produce a record with a hole in it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        samplingTask?.cancel(false)
        sampler.shutdownNow()
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    val detectorState: TripDetector.State get() = detector.state

    fun setAutomaticDetectionEnabled(enabled: Boolean) {
        if (automaticDetectionEnabled == enabled) {
            if (enabled) startAutomaticMonitor()
            return
        }
        automaticDetectionEnabled = enabled
        if (enabled) {
            if (EnergyTripSession.isRecording) detector.markRecording() else detector.reset()
            startAutomaticMonitor()
        } else {
            detector.reset()
            automaticMonitoringSuspended = false
            missingSpeedSamples.set(0)
            if (EnergyTripSession.isRecording) {
                updateNotification()
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopWhenNobodyNeedsIt()
            }
        }
    }

    /**
     * `startForegroundService` promised a notification within five seconds, so that promise is
     * kept before anything else can decide the trip cannot start — a service that returns from
     * `onStartCommand` without it is killed by the platform.
     *
     * The type mask is derived from what is actually held, never from what the manifest
     * declares: the two-argument `startForeground` claims every declared type, and an unbacked
     * claim is a SecurityException in this service's own `onCreate`.
     */
    private fun startTrip() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(forceRecording = true), foregroundTypes()
        )
        val sample = latestSnapshot
        if (sample == null) {
            AppLogger.w(TAG, "trip start ignored: no vehicle sample yet")
            if (!automaticDetectionEnabled) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } else {
                updateNotification()
            }
            stopWhenNobodyNeedsIt()
            return
        }
        beginTrip(sample)
    }

    private fun beginTrip(sample: EnergySnapshot) {
        if (!EnergyTripSession.isRecording) EnergyTripSession.start(sample)
        detector.markRecording()
        samplesSinceNotification = 0
        ensureSampling()
        updateNotification()
    }

    /** Called through the binder: the dashboard is on screen whenever a trip can be stopped. */
    fun stopTrip(onSaved: (() -> Unit)? = null) {
        val endedAt = latestSnapshot?.timestampMs ?: System.currentTimeMillis()
        val recorded = EnergyTripSession.stop(endedAt)
        detector.reset()
        if (!automaticDetectionEnabled) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        if (recorded == null) {
            if (automaticDetectionEnabled) updateNotification()
            stopWhenNobodyNeedsIt()
            return
        }
        // Appending rewrites the history file, and the tap that triggers it arrives on the main
        // thread, so the write goes to the sampler's thread — which has nothing else to do
        // between two one-second reads. Shutting the service down waits for that write: this
        // service stops itself the moment nobody needs it, and a stopSelf() racing the append
        // would drop the trip the driver just finished.
        pendingHistoryWrites.incrementAndGet()
        runCatching {
            sampler.execute {
                val saved = runCatching {
                    tripStore.append(recorded.summary, recorded.samples)
                }.onFailure {
                    AppLogger.w(TAG, "trip history write failed: ${it.message}")
                }.getOrDefault(false)
                if (!saved) AppLogger.w(TAG, "trip history could not be saved")
                pendingHistoryWrites.decrementAndGet()
                main.post {
                    runCatching { onSaved?.invoke() }
                        .onFailure { AppLogger.w(TAG, "trip saved callback failed: ${it.message}") }
                    if (automaticDetectionEnabled) updateNotification()
                    stopWhenNobodyNeedsIt()
                }
            }
        }.onFailure {
            pendingHistoryWrites.decrementAndGet()
            AppLogger.w(TAG, "trip history write could not be scheduled: ${it.message}")
            stopWhenNobodyNeedsIt()
        }
    }

    private fun ensureSampling() {
        if (samplingTask != null) return
        samplingTask = sampler.scheduleWithFixedDelay(::sample, 0L, 1L, TimeUnit.SECONDS)
    }

    private fun sample() {
        val value = runCatching { reader.read() }
            .onFailure { AppLogger.w(TAG, "telemetry sample failed: ${it.message}") }
            .getOrNull()
        if (value == null) {
            if (automaticDetectionEnabled) updateAutomaticMonitorAvailability(null)
            return
        }
        latestSnapshot = value
        EnergyTripSession.add(value)
        if (automaticDetectionEnabled) {
            updateAutomaticMonitorAvailability(value)
            val previousState = detector.state
            val result = detector.add(value)
            when (result.event) {
                TripDetector.Event.START -> beginTrip(value)
                TripDetector.Event.STOP -> stopTrip()
                null -> Unit
            }
            if (result.event == null && result.state != previousState) {
                main.post { updateNotification() }
            }
        }
        if (EnergyTripSession.isRecording && ++samplesSinceNotification >= NOTIFICATION_SAMPLES) {
            samplesSinceNotification = 0
            main.post { updateNotification() }
        }
        main.post { listener?.onSample(value) }
    }

    /** No dashboard, recording, or automatic monitor: sampling has no consumer to keep. */
    private fun stopWhenNobodyNeedsIt() {
        val automaticMonitorNeeded = automaticDetectionEnabled && !automaticMonitoringSuspended
        if (boundClients > 0 || EnergyTripSession.isRecording || automaticMonitorNeeded ||
            pendingHistoryWrites.get() > 0
        ) return
        samplingTask?.cancel(false)
        samplingTask = null
        stopSelf()
    }

    /**
     * Unsupported firmware must not leave a one-hertz foreground poll running indefinitely.
     * A bound dashboard still receives its normal samples; only idle background monitoring is
     * suspended, and opening the dashboard later performs one bounded retry.
     */
    private fun updateAutomaticMonitorAvailability(value: EnergySnapshot?) {
        if (EnergyTripSession.isRecording) {
            missingSpeedSamples.set(0)
            return
        }
        val speed = value?.speedKmh
        val usableSpeed = speed != null && speed.isFinite() && speed >= 0f
        if (usableSpeed) {
            missingSpeedSamples.set(0)
            if (automaticMonitoringSuspended) {
                automaticMonitoringSuspended = false
                main.post {
                    if (automaticDetectionEnabled && !EnergyTripSession.isRecording) {
                        startAutomaticMonitor()
                    }
                }
            }
            return
        }
        if (missingSpeedSamples.incrementAndGet() == MISSING_SPEED_SAMPLE_LIMIT) {
            automaticMonitoringSuspended = true
            AppLogger.w(TAG, "automatic trip monitor suspended: speed unavailable")
            main.post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopWhenNobodyNeedsIt()
            }
        }
    }

    private fun foregroundTypes(): Int {
        val held = ContextCompat.checkSelfPermission(
            this, "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
        ) == PackageManager.PERMISSION_GRANTED
        return if (held) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    }

    /**
     * Lint asks for POST_NOTIFICATIONS here, and the app deliberately does not hold it. The
     * target head unit runs API 28, where that permission does not exist; declaring it without
     * ever requesting it on a screen the driver reaches would grant nothing and only widen the
     * manifest. On a platform that does enforce it and has not granted it, the notification is
     * dropped and the recording continues — a missing status line, not a missing trip.
     */
    @SuppressLint("NotificationPermission")
    private fun updateNotification() {
        if (!EnergyTripSession.isRecording && !automaticDetectionEnabled) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification())
    }

    private fun startAutomaticMonitor() {
        automaticMonitoringSuspended = false
        missingSpeedSamples.set(0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundTypes())
        ensureSampling()
    }

    private fun notification(forceRecording: Boolean = false): Notification {
        val recording = forceRecording || EnergyTripSession.isRecording
        val summary = EnergyTripSession.current(System.currentTimeMillis())
        val distance = summary?.distanceKm?.let {
            String.format(Locale.getDefault(), "%.1f km", it)
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                getString(
                    if (recording) R.string.notification_recording_title
                    else R.string.notification_automatic_title
                )
            )
            .setContentText(
                if (recording) {
                    distance?.let { getString(R.string.notification_recording_distance, it) }
                        ?: getString(R.string.notification_recording_waiting)
                } else {
                    getString(
                        when (detector.state) {
                            TripDetector.State.ARMED -> R.string.trip_automatic_confirming_motion
                            else -> R.string.trip_automatic_waiting
                        }
                    )
                }
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .setShowWhen(false)
            // Low importance: it states that recording is running and asks for nothing. A
            // driver-facing interruption while the car is moving is not permitted.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START_TRIP = "com.evsuite.chargepilot.START_TRIP"
        const val ACTION_MONITOR_AUTOMATIC =
            "com.evsuite.chargepilot.MONITOR_AUTOMATIC_TRIPS"

        private const val TAG = "EVChargePilot"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES = "trip_detection"
        private const val PREF_AUTOMATIC = "automatic_enabled"
        /** One notification update per ten samples: the text changes slowly, the sampler does not. */
        private const val NOTIFICATION_SAMPLES = 10
        /** Ten one-second misses allow startup settling, then end unsupported background polling. */
        private const val MISSING_SPEED_SAMPLE_LIMIT = 10

        /**
         * Starting is an intent rather than a binder call: it is what makes the service a
         * *started* service, which is what lets it outlive the dashboard that asked for it.
         */
        fun start(context: Context) {
            val intent = Intent(context, TripRecordingService::class.java)
                .setAction(ACTION_START_TRIP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun monitorAutomaticTrips(context: Context) {
            val intent = Intent(context, TripRecordingService::class.java)
                .setAction(ACTION_MONITOR_AUTOMATIC)
            ContextCompat.startForegroundService(context, intent)
        }

        fun isAutomaticDetectionEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTOMATIC, true)

        fun storeAutomaticDetectionEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTOMATIC, enabled)
                .apply()
        }
    }
}
