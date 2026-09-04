package com.evsuite.chargepilot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityNavGuidanceProbeBinding
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.NavGuidance
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Records what the head unit's navigation actually reports during a drive.
 *
 * This is the CP-040 instrument. It answers three questions a decision needs and that no
 * amount of desk work can settle: whether the adapter accepts the registration at all, what
 * the remaining-distance callback carries and in which unit, and — the one that decides
 * whether the feature exists — whether anything arrives when no guidance is running.
 *
 * **Cost while it runs.** The listener itself is push-driven and costs nothing when the
 * navigation app is silent. This screen adds one 1 Hz tick that reads two volatile fields and
 * compares them, which is a fifth of what the dashboard already does. The trace is a bounded
 * ring; a long drive overwrites its oldest lines rather than growing. No service is started,
 * and the only write is the artifact the driver saves for the diagnostic USB export.
 *
 * The tick outlives [onStop] on purpose: a capture is meant to survive the screen going dark
 * over three hours of motorway. It stops at [onDestroy], and so does the registration.
 */
class NavGuidanceProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavGuidanceProbeBinding
    private lateinit var reader: EnergyTelemetryReader
    private lateinit var fileStore: EvidenceCaptureFileStore

    private val ticker = Handler(Looper.getMainLooper())
    private val trace = ArrayDeque<String>()
    private var lastEvents = -1
    private var visible = false
    private var startedAtElapsedMs: Long? = null
    private var dropped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavGuidanceProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reader = EnergyTelemetryReader(applicationContext)
        fileStore = EvidenceCaptureFileStore(File(filesDir, EVIDENCE_DIRECTORY))
        SaicNavGuidance.connect(applicationContext)
        binding.probeAction.setOnClickListener { toggle() }
        binding.saveAction.setOnClickListener { saveTrace() }
        ticker.post(tick)
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        render()
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        SaicNavGuidance.stop()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            record(SaicNavGuidance.latest())
            if (visible) render()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Appends a line only when the listener actually saw something new.
     *
     * A drive that produces no line is the finding, not a failure of this screen — which is
     * why the trace is never padded with repeats of an unchanged state.
     */
    private fun record(guidance: NavGuidance) {
        if (guidance.events == lastEvents) return
        lastEvents = guidance.events
        if (guidance.events == 0) return
        val since = startedAtElapsedMs?.let { (SystemClock.elapsedRealtime() - it) / 1_000L } ?: 0L
        if (trace.size >= TRACE_LINES) {
            trace.removeFirst()
            dropped = true
        }
        trace.addLast(
            String.format(
                Locale.ROOT,
                "%5ds n=%-4d status=%-4s dist=%-8s min=%-6s turn=%-8s road=%s",
                since, guidance.events,
                guidance.guideStatus.show(),
                guidance.remainingDistanceRaw.show(),
                guidance.remainingMinutes.show(),
                guidance.nextTurnDistanceRaw.show(),
                // A road name is head-unit text of unknown length; the saved artifact has to
                // stay under the export's per-file ceiling whatever it contains.
                (guidance.road ?: guidance.direction ?: DASH).take(ROAD_CHARS),
            )
        )
    }

    /** Rechecks speed at the moment of the press; a disabled button is not a safety boundary. */
    private fun toggle() {
        if (SaicNavGuidance.isListening) {
            SaicNavGuidance.stop()
            startedAtElapsedMs = null
            lastEvents = -1
            render()
            return
        }
        val speed = runCatching { reader.read().speedKmh }.getOrNull()
        if (EvidenceCapturePolicy.gate(speed) != EvidenceCaptureGate.PARKED) {
            Snackbar.make(binding.root, R.string.nav_probe_refused, Snackbar.LENGTH_LONG).show()
            return
        }
        if (SaicNavGuidance.start()) {
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            trace.clear()
            dropped = false
            lastEvents = -1
        }
        render()
    }

    /**
     * Writes the trace where the diagnostic export finds it.
     *
     * The clipboard was never a way off this head unit: the file goes into the evidence
     * folder, and the dashboard's "Export to USB" carries it out with the rest of the bundle.
     * The write is a bounded JSON file on internal storage, so it stays on the click.
     */
    private fun saveTrace() {
        val saved = fileStore.write(
            NavGuidanceProbeArtifact.of(
                savedAtMs = System.currentTimeMillis(),
                firmware = FirmwareInfo.getGeneration().name,
                adapterBound = SaicNavGuidance.isAvailable,
                listenerRegistered = SaicNavGuidance.isListening,
                callbacks = SaicNavGuidance.latest().events,
                census = SaicNavGuidance.census(),
                censusBeyondCeiling = SaicNavGuidance.censusBeyondCeiling(),
                trace = trace.toList(),
                traceComplete = !dropped,
            ).toJson(),
            NavGuidanceProbeArtifact.KIND,
            FirmwareInfo.getGeneration().name,
        )
        Snackbar.make(
            binding.root,
            if (saved == null) R.string.nav_probe_save_failed else R.string.nav_probe_saved,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun render() {
        val guidance = SaicNavGuidance.latest()
        val listening = SaicNavGuidance.isListening
        binding.probeAction.setText(
            if (listening) R.string.nav_probe_stop else R.string.nav_probe_start
        )
        binding.probeStatus.text = when {
            !SaicNavGuidance.isAvailable -> getString(R.string.nav_probe_unavailable)
            !listening -> getString(R.string.nav_probe_idle)
            guidance.events == 0 && SaicNavGuidance.census().isEmpty() ->
                getString(R.string.nav_probe_silent)
            guidance.events == 0 -> getString(R.string.nav_probe_undecoded)
            else -> getString(R.string.nav_probe_receiving, guidance.events)
        }
        binding.probeHint.setText(
            when {
                !SaicNavGuidance.isAvailable -> R.string.nav_probe_hint_unavailable
                listening -> R.string.nav_probe_hint_running
                else -> R.string.nav_probe_hint_ready
            }
        )
        binding.probeTrace.text = if (trace.isEmpty()) {
            getString(R.string.nav_probe_no_data)
        } else {
            trace.joinToString("\n")
        }
        // Always saveable. "Bound, registered, nothing received" is the outcome CP-040 most
        // needs to hear, and an unbound adapter says there is no route source on this firmware
        // at all — both are findings, and gating the button on a non-empty trace made exactly
        // the negative results the only ones that could not leave the car.
        binding.saveAction.isEnabled = true
    }

    private fun Int?.show(): String = this?.toString() ?: DASH

    private companion object {
        const val TICK_MS = 1_000L
        /** Three hours of change at one line per second would not fit; the newest lines win. */
        const val TRACE_LINES = 300
        /** A road name past this adds nothing a decision uses, and the file has a ceiling. */
        const val ROAD_CHARS = 40
        const val DASH = "—"
        const val EVIDENCE_DIRECTORY = "evidence"
    }
}
