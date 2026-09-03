package com.evsuite.chargepilot

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityNavGuidanceProbeBinding
import com.evsuite.hardware.saic.NavGuidance
import com.evsuite.hardware.saic.NavGuidanceReducer
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.google.android.material.snackbar.Snackbar
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
 * ring; a long drive overwrites its oldest lines rather than growing. Nothing is written to
 * disk and no service is started.
 *
 * The tick outlives [onStop] on purpose: a capture is meant to survive the screen going dark
 * over three hours of motorway. It stops at [onDestroy], and so does the registration.
 */
class NavGuidanceProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavGuidanceProbeBinding
    private lateinit var reader: EnergyTelemetryReader

    private val ticker = Handler(Looper.getMainLooper())
    private val trace = ArrayDeque<String>()
    private var lastEvents = -1
    private var visible = false
    private var startedAtElapsedMs: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavGuidanceProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reader = EnergyTelemetryReader(applicationContext)
        SaicNavGuidance.connect(applicationContext)
        binding.probeAction.setOnClickListener { toggle() }
        binding.copyAction.setOnClickListener { copyTrace() }
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
        if (trace.size >= TRACE_LINES) trace.removeFirst()
        trace.addLast(
            String.format(
                Locale.ROOT,
                "%5ds n=%-4d status=%-4s dist=%-8s min=%-6s turn=%-8s road=%s",
                since, guidance.events,
                guidance.guideStatus.show(),
                guidance.remainingDistanceRaw.show(),
                guidance.remainingMinutes.show(),
                guidance.nextTurnDistanceRaw.show(),
                guidance.road ?: guidance.direction ?: DASH,
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
            lastEvents = -1
        }
        render()
    }

    private fun copyTrace() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("nav-guidance", markdown()))
        Snackbar.make(binding.root, R.string.nav_probe_copied, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * The report a CP-040 decision is written from.
     *
     * It states the unit question rather than hiding it: whoever reads the trace has to say
     * what `dist` was in, and the only way to know is to compare it against a distance the
     * driver already knows.
     */
    private fun markdown(): String = buildString {
        append("# CP-040 — navigation guidance trace\n\n")
        append("- adapter bound: ${SaicNavGuidance.isAvailable}\n")
        append("- listener registered: ${SaicNavGuidance.isListening}\n")
        append("- callbacks seen: ${SaicNavGuidance.latest().events}\n")
        append("- transaction census: ${census()}\n")
        append("- codes past the census ceiling: ${SaicNavGuidance.censusBeyondCeiling()}\n")
        append("- trace lines: ${trace.size}${if (trace.size >= TRACE_LINES) " (oldest dropped)" else ""}\n\n")
        append("`dist` and `turn` are raw callback values. Their unit is unproven — state it\n")
        append("by comparing against a distance known independently before using any of this.\n\n")
        append("The transaction map was read from an R69 build. Check the census before\n")
        append("trusting any number above: traffic on codes marked `?`, or silence on the\n")
        append("decoded ones while guidance is clearly running, means this firmware numbers\n")
        append("the interface differently and every reading here is off by the same shift.\n\n")
        append("```\n")
        trace.forEach { append(it).append('\n') }
        append("```\n")
    }

    /** Decoded codes are named; anything else is flagged, because that is the shift signal. */
    private fun census(): String {
        val seen = SaicNavGuidance.census()
        if (seen.isEmpty()) return "none"
        return seen.entries.sortedBy { it.key }.joinToString(" ") { (code, count) ->
            val mark = if (code in NavGuidanceReducer.KNOWN_TRANSACTIONS) "" else "?"
            "$code$mark=$count"
        }
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
        binding.copyAction.isEnabled = trace.isNotEmpty()
    }

    private fun Int?.show(): String = this?.toString() ?: DASH

    private companion object {
        const val TICK_MS = 1_000L
        /** Three hours of change at one line per second would not fit; the newest lines win. */
        const val TRACE_LINES = 300
        const val DASH = "—"
    }
}
