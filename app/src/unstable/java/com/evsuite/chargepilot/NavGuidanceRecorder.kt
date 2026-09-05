package com.evsuite.chargepilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.NavGuidance
import com.evsuite.hardware.saic.SaicNavGuidance
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Keeps the CP-040 guidance trace for the life of the process, not the life of a screen.
 *
 * The trace used to live in the probe activity's own ring, which meant a capture ended the
 * moment the driver pressed Back, and only reached the USB bundle if they remembered a save
 * button before exporting. During validation that is the wrong default twice over: the
 * evidence is collected on a drive, when nobody should be operating a screen, and the run
 * that matters most — "the adapter published nothing" — is the one a driver is least likely
 * to think worth saving.
 *
 * So the recorder is armed when the application starts and the artifact is written when the
 * diagnostic export runs. The probe screen becomes a window onto this, not its owner.
 *
 * **Cost.** Arming is a single binder registration; the callbacks are push-driven and cost
 * nothing while the navigation is silent. The tick reads two volatile fields once a second
 * and compares them — a fifth of the dashboard's own sampling — and only runs while the
 * listener is registered. The ring is bounded, so a three-hour drive overwrites its oldest
 * lines instead of growing. Nothing is written to disk until an export asks for it.
 */
internal object NavGuidanceRecorder {

    private val ticker = Handler(Looper.getMainLooper())

    /** Guarded by itself: the tick appends on the main thread, an export reads on its own. */
    private val trace = ArrayDeque<String>()

    private var lastLine: String? = null
    private var startedAtElapsedMs: Long? = null

    @Volatile
    private var dropped = false

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** True while no line has been dropped from the ring. */
    val isTraceComplete: Boolean get() = !dropped

    fun trace(): List<String> = synchronized(trace) { trace.toList() }

    /**
     * Binds the adapter and registers the listener. Idempotent.
     *
     * Deliberately not parked-gated. Every other recording control in this app is, because it
     * writes a file or asks the driver to operate a screen; this does neither. It registers a
     * read-only listener with no UI, and refusing to arm because the car happened to be moving
     * when the app started would silently lose the drive the evidence is being collected on.
     */
    fun start(context: Context): Boolean {
        SaicNavGuidance.connect(context.applicationContext)
        if (running) return true
        if (!SaicNavGuidance.start()) return false
        running = true
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        lastLine = null
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        return true
    }

    fun stop() {
        ticker.removeCallbacks(tick)
        SaicNavGuidance.stop()
        running = false
        startedAtElapsedMs = null
        lastLine = null
    }

    /** Forgets the trace without unregistering, so a new leg starts from a clean sheet. */
    fun clear() {
        synchronized(trace) { trace.clear() }
        dropped = false
        lastLine = null
        startedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private val tick = object : Runnable {
        override fun run() {
            // readNow() polls the synchronous getters and folds in whatever the listener has
            // already seen, so a car standing still with a guidance running still reports.
            val guidance = SaicNavGuidance.readNow()
            record(guidance)
            // The car is guiding, which is the one moment CP-051's destination transactions
            // can be asked with a destination set. The probe fires once and off this thread.
            if (guidance.events > 0) ValidationProbe.onGuidanceHeard()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Appends a line only when the reported guidance actually differs from the last one.
     *
     * Deduplicating on the rendered line rather than on the callback counter is what lets the
     * synchronous getters contribute: they answer every second with the same values while the
     * car is stopped, and none of those repeats is worth a line. A drive that produces no line
     * at all is the finding, not a failure of this recorder.
     */
    private fun record(guidance: NavGuidance) {
        val since = startedAtElapsedMs?.let { (SystemClock.elapsedRealtime() - it) / 1_000L } ?: 0L
        val line = String.format(
            Locale.ROOT,
            "%5ds n=%-4d status=%-4s dist=%-8s min=%-6s turn=%-8s road=%s",
            since, guidance.events,
            guidance.guideStatus.show(),
            guidance.remainingDistanceRaw.show(),
            guidance.remainingMinutes.show(),
            guidance.nextTurnDistanceRaw.show(),
            // A road name is head-unit text of unknown length; the saved artifact has to stay
            // under the export's per-file ceiling whatever it contains.
            (guidance.road ?: guidance.direction ?: DASH).take(ROAD_CHARS),
        )
        val body = line.substringAfter("s ")
        if (body == lastLine) return
        lastLine = body
        synchronized(trace) {
            if (trace.size >= TRACE_LINES) {
                trace.removeFirst()
                dropped = true
            }
            trace.addLast(line)
        }
    }

    /** The current state as the artifact the export bundles. */
    fun artifact(nowMs: Long = System.currentTimeMillis()): NavGuidanceProbeArtifact =
        NavGuidanceProbeArtifact.of(
            savedAtMs = nowMs,
            firmware = FirmwareInfo.getGeneration().name,
            adapterBound = SaicNavGuidance.isAvailable,
            listenerRegistered = SaicNavGuidance.isListening,
            callbacks = SaicNavGuidance.latest().events,
            census = SaicNavGuidance.census(),
            censusBeyondCeiling = SaicNavGuidance.censusBeyondCeiling(),
            trace = trace(),
            traceComplete = isTraceComplete,
        )

    /**
     * Writes the artifact where the diagnostic export finds it.
     *
     * @return the file written, or null when the evidence folder refused it.
     */
    fun save(context: Context, nowMs: Long = System.currentTimeMillis()): File? {
        val firmware = FirmwareInfo.getGeneration().name
        return EvidenceCaptureFileStore(File(context.filesDir, EVIDENCE_DIRECTORY))
            .write(artifact(nowMs).toBoundedJson(), NavGuidanceProbeArtifact.KIND, firmware)
    }

    private fun Int?.show(): String = this?.toString() ?: DASH

    internal const val EVIDENCE_DIRECTORY = "evidence"
    private const val TICK_MS = 1_000L

    /** Three hours of change at one line per second would not fit; the newest lines win. */
    private const val TRACE_LINES = 300
    private const val ROAD_CHARS = 40
    private const val DASH = "—"
}
