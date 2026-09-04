package com.evsuite.chargepilot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityNavGuidanceProbeBinding
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.google.android.material.snackbar.Snackbar

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

    private val ticker = Handler(Looper.getMainLooper())
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavGuidanceProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reader = EnergyTelemetryReader(applicationContext)
        NavGuidanceRecorder.start(applicationContext)
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

    /**
     * Leaves the recorder running. It belongs to the process now, so closing this screen
     * neither unregisters the listener nor discards the trace — which is the whole reason a
     * drive no longer has to be spent with a diagnostic screen in front of the driver.
     */
    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (visible) render()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    /** Rechecks speed at the moment of the press; a disabled button is not a safety boundary. */
    private fun toggle() {
        if (NavGuidanceRecorder.isRunning) {
            NavGuidanceRecorder.stop()
            render()
            return
        }
        val speed = runCatching { reader.read().speedKmh }.getOrNull()
        if (EvidenceCapturePolicy.gate(speed) != EvidenceCaptureGate.PARKED) {
            Snackbar.make(binding.root, R.string.nav_probe_refused, Snackbar.LENGTH_LONG).show()
            return
        }
        NavGuidanceRecorder.start(applicationContext)
        NavGuidanceRecorder.clear()
        render()
    }

    /**
     * Saves now, rather than waiting for the next export to do it.
     *
     * The export writes this artifact on its own, so this button is a convenience for a
     * driver who wants the current state pinned before carrying on.
     */
    private fun saveTrace() {
        val saved = NavGuidanceRecorder.save(applicationContext)
        Snackbar.make(
            binding.root,
            if (saved == null) R.string.nav_probe_save_failed else R.string.nav_probe_saved,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private fun render() {
        val guidance = SaicNavGuidance.latest()
        val listening = NavGuidanceRecorder.isRunning
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
        val trace = NavGuidanceRecorder.trace()
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

    private companion object {
        const val TICK_MS = 1_000L
    }
}
