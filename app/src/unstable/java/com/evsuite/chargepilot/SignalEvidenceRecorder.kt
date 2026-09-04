package com.evsuite.chargepilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.TelemetryEvidenceRecorder

/**
 * Signal statistics for the whole session, without anyone starting a capture.
 *
 * The manual capture screen answers the same question, but only for the window somebody
 * remembered to open it — and the questions still outstanding are about what a signal does
 * *on a drive*: whether battery power ever publishes, and what `PERF_VEHICLE_SPEED` actually
 * ranges over. A trip recorded 7 km for a 2.4 km drive, which is the m/s-to-km/h factor, and
 * the only thing that settles it is the maximum speed this recorder saw against a speedometer
 * the driver was reading anyway.
 *
 * **Cost.** One property read a second, the same cadence the recording service already uses,
 * and constant memory: each signal keeps running min, max, mean, null and sign counts, never
 * the samples. Unstable only.
 */
internal object SignalEvidenceRecorder {

    private const val TAG = "EVChargePilot"
    private const val TICK_MS = 1_000L

    private val ticker = Handler(Looper.getMainLooper())
    private var reader: EnergyTelemetryReader? = null

    /** Guarded by itself: the tick writes on the main thread, an export reads on its own. */
    private val recorder = TelemetryEvidenceRecorder()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun start(context: Context) {
        if (running) return
        reader = EnergyTelemetryReader(context.applicationContext)
        running = true
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stop() {
        ticker.removeCallbacks(tick)
        running = false
    }

    fun capture(): EvidenceCapture = synchronized(recorder) { recorder.capture() }

    private val tick = object : Runnable {
        override fun run() {
            val source = reader
            if (source != null) {
                // A read that throws is a reading we do not have, not a reason to stop the
                // session's statistics: the next second is tried exactly the same way.
                runCatching { source.readEvidence(System.currentTimeMillis()) }
                    .onFailure { AppLogger.d(TAG, "signal evidence sample failed: ${it.message}") }
                    .getOrNull()
                    ?.let { sample -> synchronized(recorder) { recorder.record(sample) } }
            }
            ticker.postDelayed(this, TICK_MS)
        }
    }
}
