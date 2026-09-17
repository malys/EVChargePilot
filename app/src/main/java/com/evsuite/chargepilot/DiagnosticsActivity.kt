package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.evsuite.chargepilot.databinding.ActivityDiagnosticsBinding
import com.evsuite.chargepilot.update.UpdateHook
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import java.io.File
import java.util.concurrent.Executors

/**
 * The diagnostics report as a page of its own.
 *
 * It used to be a dialog raised from the dashboard, which cost the trip rail a permanent button
 * and hid a long report behind a scroll inside a scroll. As a page it is reachable from every
 * screen through the navigation bar, and the report has the room it always needed.
 *
 * The recorder is bound here for the same reason the dashboard binds it: binding is what makes
 * the service sample, and the export gate needs a speed reading no older than five seconds.
 */
class DiagnosticsActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.DIAGNOSTICS

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var provenance: ProvenanceText

    /** Property probes, the crash file and the log tail are disk and binder work, never main. */
    private val background = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-diagnostics")
    }

    private var recorder: TripRecordingService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recorder = (service as? TripRecordingService.LocalBinder)?.service
            // The first report was built before the service answered; rebuild it now that the
            // [service] section has something to say.
            renderReport()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        provenance = ProvenanceText(this)
        binding.diagnosticsExportAction.setOnClickListener { exportDiagnostics() }
        binding.diagnosticsEvidenceAction.visibility =
            if (EvidenceCaptureHook.IS_SUPPORTED) View.VISIBLE else View.GONE
        binding.diagnosticsEvidenceAction.setOnClickListener { chooseEvidenceScenario() }
    }

    override fun onStart() {
        super.onStart()
        renderReport()
        bindService(
            Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        recorder = null
        unbindService(connection)
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    /** The report reads ten vehicle properties over binder and the crash file off disk. */
    private fun renderReport() {
        background.execute {
            val body = diagnosticsReport()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.diagnosticsReport.text = body
            }
        }
    }

    /** Export remains a parked, explicit action; missing speed fails closed with a reason. */
    private fun exportDiagnostics() {
        if (showDiagnosticExportRefusal(diagnosticExportDecision(recorder?.latest))) return
        val appContext = applicationContext
        background.execute {
            val roots = DiagnosticUsbStorage.roots(appContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    toastLong(R.string.diagnostics_export_no_usb)
                } else {
                    chooseDiagnosticDestination(roots)
                }
            }
        }
    }

    /** Browsed, not listed: the bundle lands in the folder the driver will go looking in. */
    private fun chooseDiagnosticDestination(roots: List<File>) {
        StorageBrowserDialog.pickFolder(this, roots, R.string.diagnostics_export_pick_usb) {
            writeDiagnostic(it)
        }
    }

    private fun writeDiagnostic(directory: File) {
        val appContext = applicationContext
        val service = recorder
        background.execute {
            // Root discovery and the user's choice can take arbitrarily long. Re-read the
            // volatile latest sample at the last responsible moment so parking cannot go stale.
            val decision = diagnosticExportDecision(service?.latest)
            val file = if (decision == DiagnosticExportPolicy.Decision.ALLOWED) {
                // Every always-on probe writes its current artifact first, so one export
                // carries the guidance trace, the signal statistics and the trip record
                // without anyone having remembered to save them during the drive.
                EvidenceCaptureHook.saveProbeArtifacts(appContext)
                // Rebuild after USB selection so timestamps, logs and last sample match export.
                DiagnosticExporter.export(appContext, diagnosticsReport(), directory)
            } else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (showDiagnosticExportRefusal(decision)) {
                    Unit
                } else if (file == null) {
                    toastLong(R.string.diagnostics_export_failed)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.diagnostics_export_ok, file.absolutePath),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun diagnosticExportDecision(sample: EnergySnapshot?): DiagnosticExportPolicy.Decision =
        DiagnosticExportPolicy.decide(
            speedKmh = sample?.speedKmh,
            sampledAtMs = sample?.timestampMs,
            nowMs = System.currentTimeMillis(),
        )

    /** @return true when a visible refusal was shown. */
    private fun showDiagnosticExportRefusal(decision: DiagnosticExportPolicy.Decision): Boolean =
        when (decision) {
            DiagnosticExportPolicy.Decision.SPEED_UNAVAILABLE -> {
                toastLong(R.string.diagnostics_export_speed_unavailable)
                true
            }
            DiagnosticExportPolicy.Decision.VEHICLE_MOVING -> {
                toastLong(R.string.diagnostics_export_park_vehicle)
                true
            }
            DiagnosticExportPolicy.Decision.ALLOWED -> false
        }

    private fun toastLong(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun chooseEvidenceScenario() {
        val scenarios = VehicleTestContextStore.Scenario.entries
        val labels = scenarios.map { getString(it.labelResource()) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.evidence_scenario_title)
            .setItems(labels) { _, which ->
                val scenario = scenarios[which]
                if (VehicleTestContextStore(filesDir).write(scenario)) {
                    AppLogger.i(TAG, "vehicle test scenario selected; scenario=${scenario.id}")
                    EvidenceCaptureHook.open(this)
                } else {
                    toastLong(R.string.evidence_scenario_write_failed)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun VehicleTestContextStore.Scenario.labelResource(): Int = when (this) {
        VehicleTestContextStore.Scenario.STATIONARY_HVAC_OFF ->
            R.string.evidence_scenario_stationary_hvac_off
        VehicleTestContextStore.Scenario.STATIONARY_HVAC_MAX ->
            R.string.evidence_scenario_stationary_hvac_max
        VehicleTestContextStore.Scenario.URBAN_ACCEL_REGEN ->
            R.string.evidence_scenario_urban_accel_regen
        VehicleTestContextStore.Scenario.MOTORWAY_110 -> R.string.evidence_scenario_motorway_110
        VehicleTestContextStore.Scenario.MOTORWAY_130 -> R.string.evidence_scenario_motorway_130
        VehicleTestContextStore.Scenario.GRADE_UPHILL -> R.string.evidence_scenario_grade_uphill
        VehicleTestContextStore.Scenario.GRADE_DOWNHILL -> R.string.evidence_scenario_grade_downhill
        VehicleTestContextStore.Scenario.CHARGING -> R.string.evidence_scenario_charging
        VehicleTestContextStore.Scenario.OTHER -> R.string.evidence_scenario_other
    }

    private fun diagnosticsReport(): String {
        val crash = CrashLogger.read(applicationContext)
        val logEntries = AppLogger.entries
        val recentLog = DiagnosticExporter.boundedTail(logEntries.joinToString("\n") {
            "[${it.time}] ${it.level}/${it.tag}: ${it.msg.take(MAX_LOG_MESSAGE_CHARS)}" +
                if (it.msg.length > MAX_LOG_MESSAGE_CHARS) " [message truncated]" else ""
        }, MAX_LOG_SECTION_BYTES, "[older log entries omitted]\n")
        val snapshot = recorder?.latest
        val nowMs = System.currentTimeMillis()
        val service = recorder
        val testContext = VehicleTestContextStore(filesDir).read()
        return DiagnosticExporter.bounded(buildString {
            appendLine("[report]")
            appendLine("schema=2")
            appendLine("producer=EVChargePilot/Codex")
            appendLine("purpose=real-vehicle-validation")
            appendLine()
            appendLine("[runtime]")
            DiagnosticRuntimeContext.collect(applicationContext, nowMs).forEach(::appendLine)
            appendLine()
            appendLine("[firmware]")
            appendLine(getString(R.string.diagnostics_firmware, FirmwareInfo.getGeneration().name))
            appendLine("detected_firmware=${FirmwareInfo.getDetectedString()}")
            appendLine("compatibility_forced=${FirmwareInfo.isForced(applicationContext)}")
            appendLine(
                "battery_power_evidence=" +
                    (CarPropertyEvidence.batteryPowerEvidence(FirmwareInfo.getGeneration())
                        ?.toString() ?: "unvalidated")
            )
            appendLine(getString(R.string.diagnostics_read_only))
            appendLine()
            appendLine("[vehicle_test_context]")
            appendLine("scenario=${testContext?.scenario?.id ?: "unclassified"}")
            appendLine("selected_epoch_ms=${testContext?.selectedAtMs ?: "unavailable"}")
            appendLine()
            appendLine("[service]")
            appendLine("bound=${service != null}")
            appendLine("recording=${service?.isRecording ?: EnergyTripSession.isRecording}")
            appendLine("automatic_detection=${service?.automaticDetectionEnabled ?: "unavailable"}")
            appendLine("detector_state=${service?.detectorState?.name ?: "unavailable"}")
            appendLine("automatic_monitor_suspended=${service?.isAutomaticMonitorSuspended ?: "unavailable"}")
            appendLine("missing_speed_samples=${service?.missingSpeedSampleCount ?: "unavailable"}")
            appendLine("bound_clients=${service?.boundClientCount ?: 0}")
            appendLine("pending_history_writes=${service?.pendingHistoryWriteCount ?: "unavailable"}")
            appendLine("latest_sample_age_ms=${snapshot?.timestampMs?.let(nowMs::minus) ?: "unavailable"}")
            appendLine("app_log_total_since_start=${AppLogger.totalCount}")
            appendLine("app_log_retained=${logEntries.size}")
            appendLine()
            appendLine("[panel]")
            panelReport().forEach(::appendLine)
            appendLine()
            appendLine("[internal_storage_artifacts]")
            diagnosticFileInventory().forEach(::appendLine)
            appendLine()
            appendLine("[latest_normalized_snapshot]")
            DiagnosticSnapshotFormatter.format(snapshot).forEach(::appendLine)
            appendLine()
            // A field showing an em dash says the signal is unusable but not why. This says why:
            // unsupported, declared and never published, or unreachable on this runtime.
            appendLine("[property_probe]")
            appendLine(getString(R.string.diagnostics_properties))
            appendLine(getString(R.string.diagnostics_properties_hint))
            EVHardware.probeTelemetryProperties().forEach { appendLine(it.toString()) }
            appendLine()
            // The dashboard has room for an em dash and not for a sentence. The report is
            // where the sentence goes: per field, what kind of value it is and why it is
            // missing when it is.
            appendLine("[provenance]")
            appendLine(getString(R.string.diagnostics_provenance))
            provenance.describeAll(DashboardFrame.readings).forEach { appendLine(it) }
            appendLine()
            // The update channel answers for itself: which channel this APK is, and what the
            // last pass over the pipeline concluded. A head unit has no adb, so this section
            // and the USB export are the only way that verdict leaves the car.
            appendLine("[update]")
            UpdateHook.diagnosis(applicationContext).forEach(::appendLine)
            appendLine()
            appendLine("[app_log]")
            appendLine(getString(R.string.diagnostics_recent_log))
            append(recentLog.ifBlank { getString(R.string.diagnostics_no_log) })
            appendLine()
            appendLine()
            appendLine("[previous_crash]")
            appendLine(crash ?: "none")
        })
    }

    /**
     * Exactly what the resource system sees, so a layout no longer has to guess the panel.
     *
     * The car draws no system bars and its per-app DPI is the driver's to set, so neither the
     * pixel size nor the density is knowable from a desk — and a screen sized for the emulator
     * came out squashed in the vehicle. `screen_height_dp` is the figure a `-hXXXdp` qualifier
     * matches on, and `window_*` is what an activity is actually handed once the insets the
     * platform reserves are taken out. Reported rather than adapted to: one capture settles it.
     */
    private fun panelReport(): List<String> {
        val config = resources.configuration
        val metrics = resources.displayMetrics
        val lines = mutableListOf(
            "screen_width_dp=${config.screenWidthDp}",
            "screen_height_dp=${config.screenHeightDp}",
            "smallest_screen_width_dp=${config.smallestScreenWidthDp}",
            "density_dpi=${config.densityDpi}",
            "density=${metrics.density}",
            "scaled_density=${metrics.scaledDensity}",
            "font_scale=${config.fontScale}",
            "display_px=${metrics.widthPixels}x${metrics.heightPixels}",
            "xdpi=${metrics.xdpi};ydpi=${metrics.ydpi}",
            "ui_mode_night=${
                config.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }",
            "orientation=${config.orientation}",
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lines += "window_metrics=unavailable below API 30"
            return lines
        }
        val window = windowManager.currentWindowMetrics
        val bounds = window.bounds
        val insets = window.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        lines += "window_px=${bounds.width()}x${bounds.height()}"
        lines += "system_bar_insets_px=l${insets.left};t${insets.top};r${insets.right};b${insets.bottom}"
        // What a full-screen layout really has, in the unit the layouts are written in.
        val usableHeightDp = (bounds.height() - insets.top - insets.bottom) / metrics.density
        val usableWidthDp = (bounds.width() - insets.left - insets.right) / metrics.density
        lines += "usable_dp=${usableWidthDp.toInt()}x${usableHeightDp.toInt()}"
        return lines
    }

    /** Metadata only: no trip contents, location, VIN or other user data enters diagnostics. */
    private fun diagnosticFileInventory(): List<String> {
        val files = runCatching {
            filesDir.walkTopDown().filter(File::isFile).toList()
        }.getOrDefault(emptyList())
        val lines = files.sortedBy { it.relativeTo(filesDir).path }.take(MAX_INVENTORY_FILES).map {
            "${it.relativeTo(filesDir).path};bytes=${it.length()};modified_epoch_ms=${it.lastModified()}"
        }.toMutableList()
        if (files.size > MAX_INVENTORY_FILES) {
            lines += "inventory_truncated=${files.size - MAX_INVENTORY_FILES}"
        }
        if (lines.isEmpty()) lines += "none"
        return lines
    }

    private companion object {
        const val TAG = "EVChargePilot"
        const val MAX_LOG_MESSAGE_CHARS = 1_024
        const val MAX_LOG_SECTION_BYTES = 32 * 1_024
        const val MAX_INVENTORY_FILES = 64
    }
}
