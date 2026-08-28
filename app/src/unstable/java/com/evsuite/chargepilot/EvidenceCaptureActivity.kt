package com.evsuite.chargepilot

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityEvidenceCaptureBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.telemetry.SignalKind
import com.evsuite.hardware.telemetry.TelemetryEvidenceFormat
import com.evsuite.hardware.telemetry.TelemetryEvidenceRecorder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Records what each energy signal actually does on the car in front of you.
 *
 * The driver starts and stops this development capture while parked. All reads and all access
 * to [TelemetryEvidenceRecorder] stay on [sampler], because that recorder is deliberately
 * thread-confined. The UI receives only immutable snapshots of its state.
 */
class EvidenceCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEvidenceCaptureBinding
    private lateinit var reader: EnergyTelemetryReader
    private lateinit var fileStore: EvidenceCaptureFileStore

    private val sampler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chargepilot-evidence")
    }
    private var tick: ScheduledFuture<*>? = null

    // Sampler-thread confined state. Do not read any of these fields from the main thread.
    private val previewRecorder = TelemetryEvidenceRecorder()
    private var recorder: TelemetryEvidenceRecorder? = null
    private var latest: EnergySnapshot? = null
    private var lastCapture: EvidenceCapture? = null
    private var captureStartedElapsedMs: Long? = null
    private var lastReadElapsedMs: Long? = null
    private var lastWrittenFile: File? = null
    private var writeFailed = false

    // Main-thread state used only by the clipboard action.
    private var displayedCapture: EvidenceCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEvidenceCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reader = EnergyTelemetryReader(applicationContext)
        fileStore = EvidenceCaptureFileStore(File(filesDir, "evidence"))
        binding.captureAction.setOnClickListener { requestToggle() }
        binding.copyAction.setOnClickListener { copyMarkdown() }
        render(CaptureViewState.empty())
    }

    override fun onStart() {
        super.onStart()
        // A 200 ms clock supports the bounded burst; policy reduces ordinary reads to 1 Hz.
        tick = sampler.scheduleWithFixedDelay(::onTick, 0L, TICK_MS, TimeUnit.MILLISECONDS)
    }

    override fun onStop() {
        tick?.cancel(false)
        tick = null
        super.onStop()
    }

    override fun onDestroy() {
        sampler.shutdownNow()
        super.onDestroy()
    }

    private fun onTick() {
        val elapsedNow = SystemClock.elapsedRealtime()
        val sinceLastRead = lastReadElapsedMs?.let(elapsedNow::minus)
        val captureElapsed = captureStartedElapsedMs?.let(elapsedNow::minus) ?: 0L
        if (!EvidenceCapturePolicy.shouldRead(recorder != null, captureElapsed, sinceLastRead)) {
            return
        }
        lastReadElapsedMs = elapsedNow
        val snapshot = runCatching { reader.read(System.currentTimeMillis()) }
            .onFailure { AppLogger.w(TAG, "evidence sample failed: ${it.message}") }
            .getOrNull() ?: return
        latest = snapshot
        // The table is live even before a file capture starts. This preview remains in memory and
        // is never passed to the file store; opening diagnostics alone cannot create an artifact.
        previewRecorder.record(snapshot)
        recorder?.record(snapshot)
        publish()
    }

    /** Rechecks speed on the sampler thread; a disabled button is not a safety boundary. */
    private fun requestToggle() {
        binding.captureAction.isEnabled = false
        sampler.execute {
            when (EvidenceCapturePolicy.gate(latest?.speedKmh)) {
                EvidenceCaptureGate.PARKED -> {
                    if (recorder == null) startCapture() else stopCapture()
                    publish()
                }
                EvidenceCaptureGate.SPEED_UNAVAILABLE,
                EvidenceCaptureGate.MOVING,
                -> publish(commandRefused = true)
            }
        }
    }

    private fun startCapture() {
        captureStartedElapsedMs = SystemClock.elapsedRealtime()
        recorder = TelemetryEvidenceRecorder()
        lastCapture = null
        lastWrittenFile = null
        writeFailed = false
    }

    private fun stopCapture() {
        val capture = recorder?.capture() ?: return
        recorder = null
        captureStartedElapsedMs = null
        lastCapture = capture
        lastWrittenFile = fileStore.write(capture)
        writeFailed = lastWrittenFile == null
        if (writeFailed) AppLogger.w(TAG, "evidence file could not be written")
    }

    private fun publish(commandRefused: Boolean = false) {
        val capture = recorder?.capture() ?: lastCapture ?: previewRecorder.capture()
        if (recorder != null) lastCapture = capture
        val state = CaptureViewState(
            speedKmh = latest?.speedKmh,
            recording = recorder != null,
            capture = capture,
            captureStartedElapsedMs = captureStartedElapsedMs,
            writtenFile = lastWrittenFile,
            writeFailed = writeFailed,
            commandRefused = commandRefused,
        )
        runOnUiThread {
            if (!isFinishing && !isDestroyed) render(state)
        }
    }

    /**
     * The Markdown goes to the clipboard rather than through a share sheet: this app holds no
     * network or storage permission, and the JSON remains app-private for adb retrieval.
     */
    private fun copyMarkdown() {
        val capture = displayedCapture ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("evidence", TelemetryEvidenceFormat.toMarkdown(capture))
        )
        Snackbar.make(binding.root, R.string.evidence_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun render(state: CaptureViewState) {
        displayedCapture = state.capture
        val gate = EvidenceCapturePolicy.gate(state.speedKmh)

        binding.captureAction.isEnabled = gate == EvidenceCaptureGate.PARKED
        binding.captureAction.setText(
            if (state.recording) R.string.evidence_stop else R.string.evidence_start
        )
        binding.captureHint.setText(
            when {
                gate == EvidenceCaptureGate.SPEED_UNAVAILABLE -> R.string.evidence_speed_unavailable
                gate == EvidenceCaptureGate.MOVING -> R.string.evidence_park_to_change
                state.recording -> R.string.evidence_recording
                else -> R.string.evidence_ready
            }
        )

        binding.captureStatus.text = when {
            state.commandRefused -> getString(R.string.evidence_refused)
            state.recording -> getString(
                R.string.evidence_capturing,
                state.capture?.snapshots ?: 0,
                state.captureStartedElapsedMs?.let(SystemClock.elapsedRealtime()::minus)
                    ?.div(1_000L) ?: 0L,
            )
            state.writeFailed -> getString(R.string.evidence_write_failed)
            state.writtenFile != null -> getString(R.string.evidence_saved)
            else -> getString(R.string.evidence_idle)
        }
        binding.capturePath.text = state.writtenFile?.absolutePath.orEmpty()
        binding.capturePath.visibility = if (state.writtenFile == null) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        binding.evidenceTable.text = state.capture?.let(::table)
            ?: getString(R.string.evidence_no_data)
        binding.copyAction.isEnabled = state.capture != null
    }

    private fun table(capture: EvidenceCapture): String = buildString {
        append(String.format(
            Locale.ROOT, "%-28s %6s %6s %10s %10s %10s %8s\n",
            "signal", "n", "nulls", "min", "max", "mean", "period",
        ))
        capture.signals.forEach { evidence ->
            val sign = if (evidence.kind == SignalKind.NUMERIC) {
                "  +${evidence.positive ?: 0}/-${evidence.negative ?: 0}"
            } else {
                ""
            }
            append(String.format(
                Locale.ROOT, "%-28s %6d %6d %10s %10s %10s %8s%s\n",
                evidence.signal, evidence.samples, evidence.nulls,
                evidence.min.short(), evidence.max.short(), evidence.mean.short(),
                evidence.updatePeriodMedianMs?.toString() ?: DASH, sign,
            ))
        }
    }

    private fun Double?.short(): String =
        this?.let { String.format(Locale.ROOT, "%.2f", it) } ?: DASH

    private data class CaptureViewState(
        val speedKmh: Float?,
        val recording: Boolean,
        val capture: EvidenceCapture?,
        val captureStartedElapsedMs: Long?,
        val writtenFile: File?,
        val writeFailed: Boolean,
        val commandRefused: Boolean,
    ) {
        companion object {
            fun empty() = CaptureViewState(
                speedKmh = null,
                recording = false,
                capture = null,
                captureStartedElapsedMs = null,
                writtenFile = null,
                writeFailed = false,
                commandRefused = false,
            )
        }
    }

    private companion object {
        const val TAG = "EVChargePilot"
        const val DASH = "—"
        const val TICK_MS = 200L
    }
}
