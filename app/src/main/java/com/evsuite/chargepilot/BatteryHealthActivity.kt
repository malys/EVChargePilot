package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import com.evsuite.chargepilot.databinding.ActivityBatteryHealthBinding
import com.evsuite.hardware.telemetry.BatteryExposureReport
import com.evsuite.hardware.telemetry.CalibrationDriftReport
import com.evsuite.hardware.telemetry.CalibrationVerdict
import com.evsuite.hardware.telemetry.ChargeEnergyReport
import com.evsuite.hardware.telemetry.SohEnergySource
import com.evsuite.hardware.telemetry.StateOfHealthEstimate
import com.evsuite.hardware.telemetry.StateOfHealthResult
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * What the pack is worth today, what its gauge is anchored on, and what it has been through.
 *
 * Three answers from one ledger, kept apart on purpose. The health estimate is a ratio this app
 * measured (CP-070), the calibration verdict is an observation about the car's own charge gauge
 * (CP-071), and the exposure block is history rather than diagnosis (CP-072). A single score
 * would hide which of them moved.
 *
 * **Read-only, like the rest of this app.** Nothing here sets a charge limit, a schedule or the
 * battery preheat; those are vehicle writes and they live in EVProfile and EVTasker. The one
 * thing this screen can change is *this app's own* declared state of health, which is a setting
 * — so it is offered as a suggestion, gated parked-only like every other setting, and the
 * driver's figure stays theirs until they tap.
 */
class BatteryHealthActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.BATTERY


    private lateinit var binding: ActivityBatteryHealthBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-battery-health")
    }

    private var recorder: TripRecordingService? = null
    private var loadedBatteryRevision = -1
    private var bound = false
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null

    private var health: StateOfHealthResult? = null
    private var drift: CalibrationDriftReport? = null
    private var exposure: BatteryExposureReport? = null
    private var charge: ChargeEnergyReport? = null
    private var declared = VehicleSettings.Values()
    private var applied: String? = null

    private val gateExpiry = Runnable { renderApply() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            if (ChargeLearningDefaults.AUTO_REFRESH) {
                loadedBatteryRevision = value.batteryRevision
                load()
            }
            value.setListener(this@BatteryHealthActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                if (
                    ChargeLearningDefaults.AUTO_REFRESH &&
                    value.batteryRevision != loadedBatteryRevision
                ) {
                    loadedBatteryRevision = value.batteryRevision
                    load()
                }
                renderApply()
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            renderApply()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            speedKmh = null
            speedObservedAtMs = null
            renderApply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.applyAction.setOnClickListener { apply() }
        load()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) renderApply()
    }

    override fun onStop() {
        recorder?.clearListener(this)
        recorder = null
        binding.root.removeCallbacks(gateExpiry)
        if (bound) unbindService(connection)
        bound = false
        speedKmh = null
        speedObservedAtMs = null
        super.onStop()
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        disk.execute {
            val settings = VehicleSettings.read(this)
            val learned = LocalBatteryLearning.refresh(
                filesDir,
                settings.usableCapacityKwhWhenNew,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                declared = settings
                health = learned.health
                drift = learned.drift
                exposure = learned.exposure
                charge = learned.charge
                render()
            }
        }
    }

    private fun render() {
        renderHealth()
        renderCalibration()
        renderCharge()
        renderExposure()
        renderApply()
    }

    private fun renderHealth() {
        val text = ProvenanceText(this)
        when (val current = health) {
            is StateOfHealthResult.Ready -> {
                val estimate = current.estimate
                binding.healthValue.text =
                    text.render(estimate.stateOfHealthPercent, PATTERN_SOC)
                binding.healthTrend.text = trendText(estimate)
                binding.healthEvidence.text = evidenceText(estimate, text)
            }
            is StateOfHealthResult.Unavailable, null -> {
                val seen = (current as? StateOfHealthResult.Unavailable)?.windowsSeen ?: 0
                binding.healthValue.text = getString(R.string.value_unavailable)
                binding.healthTrend.text = getString(R.string.battery_health_read_only)
                binding.healthEvidence.text = if (seen == 0 && current != null) {
                    getString(R.string.battery_health_unavailable_no_source)
                } else {
                    getString(
                        R.string.battery_health_unavailable,
                        StateOfHealthEstimator.MIN_SOC_DROP_PERCENT,
                        StateOfHealthEstimator.MIN_WINDOWS,
                        seen,
                    )
                }
            }
        }
    }

    private fun trendText(estimate: StateOfHealthEstimate): String {
        val trend = estimate.trendPercentPoints
            ?: return getString(R.string.battery_health_trend_unavailable)
        // Signed on purpose: a pack that measures better than it did is as much a fact about
        // the estimator's own scatter as one that measures worse, and hiding the sign would
        // turn the second into the only thing this line can ever say.
        val points = String.format(Locale.getDefault(), PATTERN_TREND, trend)
        return getString(R.string.battery_health_trend, points, estimate.spanDays)
    }

    private fun evidenceText(estimate: StateOfHealthEstimate, text: ProvenanceText): String {
        val source = getString(
            when (estimate.source) {
                SohEnergySource.VEHICLE_COUNTERS -> R.string.battery_health_source_counters
                SohEnergySource.PACK_PAIR_INTEGRAL -> R.string.battery_health_source_pack_pair
            }
        )
        val capacity = text.render(estimate.usableCapacityKwh, PATTERN_ENERGY)
        val evidence = if (estimate.coldWindowsExcluded > 0) {
            getString(
                R.string.battery_health_evidence_cold,
                source,
                estimate.windowCount,
                estimate.coldWindowsExcluded,
                estimate.spanDays,
                capacity,
            )
        } else {
            getString(
                R.string.battery_health_evidence,
                source,
                estimate.windowCount,
                estimate.spanDays,
                capacity,
            )
        }
        val declaredLine = getString(
            R.string.battery_health_declared,
            declared.stateOfHealthPercent,
            declared.usableCapacityKwhWhenNew,
        )
        return "$evidence\n$declaredLine"
    }

    private fun renderCalibration() {
        val report = drift
        if (report == null) {
            binding.calibrationVerdict.text =
                getString(R.string.battery_health_calibration_unavailable)
            binding.calibrationFacts.text = ""
            return
        }
        val days = report.daysSinceFullCharge
        binding.calibrationVerdict.text = when {
            report.verdict == CalibrationVerdict.RE_ANCHOR_SUGGESTED ->
                getString(R.string.battery_health_calibration_re_anchor)
            report.verdict == CalibrationVerdict.WATCH && days != null ->
                getString(R.string.battery_health_calibration_watch, days)
            days != null -> getString(R.string.battery_health_calibration_settled, days)
            else -> getString(R.string.battery_health_calibration_unavailable)
        }
        val facts = ArrayList<String>()
        if (days == null) {
            facts += getString(
                R.string.battery_health_calibration_never_full,
                CalibrationDrift.FULL_SOC_PERCENT,
            )
        } else {
            facts += getString(
                R.string.battery_health_calibration_cycles,
                report.equivalentCyclesSinceFullCharge,
            )
        }
        if (report.steps.isNotEmpty()) {
            facts += getString(
                R.string.battery_health_calibration_steps,
                report.steps.size,
                report.steps.maxOf { it.dropPercent },
            )
        }
        binding.calibrationFacts.text = facts.joinToString("\n")
    }

    /**
     * What the last charge actually cost the grid, which is the half of the ledger the health
     * estimate never reads: it measures discharges only.
     *
     * A rise in charge is how a charge is found at all here, and a long descent raises the
     * charge too — so only a session the odometer says stood still is offered as "your last
     * charge". The energy is stated as approximate and with its source named, because it is an
     * integral of a pack voltage-current pair whose sign convention no drive has yet confirmed;
     * the magnitude is the same either way, which is why the line can be shown while RI-002 is
     * still open.
     */
    private fun renderCharge() {
        val last = charge?.lastPluggedCharge
        if (last == null) {
            binding.chargeFacts.text = getString(R.string.battery_health_last_charge_none)
            return
        }
        val energy = last.packDeltaKwh?.takeIf { last.watched }
        val power = last.meanPowerKw
        binding.chargeFacts.text = if (energy != null && power != null) {
            getString(
                R.string.battery_health_last_charge,
                last.session.gainedPercent,
                last.session.durationHours,
                abs(energy),
                power,
            )
        } else {
            getString(
                R.string.battery_health_last_charge_unwatched,
                last.session.gainedPercent,
                last.session.durationHours,
            )
        }
    }

    private fun renderExposure() {
        val report = exposure
        if (report == null || report.observedHours <= 0.0) {
            binding.exposureFacts.text = getString(R.string.battery_health_exposure_unavailable)
            return
        }
        val lines = ArrayList<String>()
        lines += getString(
            R.string.battery_health_exposure_dwell,
            report.hoursAboveHighSoc * 100.0 / report.observedHours,
            BatteryExposure.HIGH_SOC_PERCENT,
            report.hoursBelowLowSoc * 100.0 / report.observedHours,
            BatteryExposure.LOW_SOC_PERCENT,
            report.meanSocPercent,
        )
        lines += getString(
            R.string.battery_health_exposure_cycles,
            report.equivalentFullCycles,
            report.spanDays,
        )
        report.kmPerEquivalentCycle?.let {
            lines += getString(R.string.battery_health_exposure_km, it)
        }
        if (report.sessions.isNotEmpty()) {
            lines += getString(
                R.string.battery_health_exposure_sessions,
                report.sessions.size,
                report.sessions.maxOf { it.peakPercentPerHour },
            )
        }
        lines += getString(R.string.battery_health_read_only)
        binding.exposureFacts.text = lines.joinToString("\n")
    }

    /**
     * The suggestion, and the gate in front of it.
     *
     * The button is only ever shown for a figure the settings would accept: an estimate outside
     * that band is a statement about where the energy was counted rather than about the cells,
     * and offering it as a setting would put it into every arrival forecast.
     */
    private fun renderApply() {
        binding.root.removeCallbacks(gateExpiry)
        val suggestion = (health as? StateOfHealthResult.Ready)
            ?.estimate?.stateOfHealthPercent?.value
        val done = applied
        if (suggestion == null || !VehicleSettings.isHealthAcceptable(suggestion)) {
            binding.applyAction.visibility = View.GONE
            binding.applyStatus.text = done.orEmpty()
            return
        }
        binding.applyAction.visibility = View.VISIBLE
        if (done != null) {
            binding.applyStatus.text = done
            return
        }
        val nowMs = System.currentTimeMillis()
        when (ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)) {
            ParkedDeletionGate.PARKED -> {
                val ageMs = nowMs - checkNotNull(speedObservedAtMs)
                binding.root.postDelayed(
                    gateExpiry,
                    ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
                )
                binding.applyAction.isEnabled = true
                binding.applyStatus.text = ""
            }
            ParkedDeletionGate.MOVING -> {
                binding.applyAction.isEnabled = false
                binding.applyStatus.text =
                    getString(R.string.battery_health_apply_parked_only)
            }
            ParkedDeletionGate.SPEED_UNAVAILABLE -> {
                binding.applyAction.isEnabled = false
                binding.applyStatus.text =
                    getString(R.string.battery_health_apply_speed_unknown)
            }
        }
    }

    private fun apply() {
        val estimate = (health as? StateOfHealthResult.Ready)?.estimate ?: return
        val suggestion = estimate.stateOfHealthPercent.value ?: return
        if (!VehicleSettings.isHealthAcceptable(suggestion)) return
        // The gate is re-read here rather than trusted from the last render: a tap can land
        // after the car has started moving and before the listener said so.
        if (ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, System.currentTimeMillis())
            != ParkedDeletionGate.PARKED
        ) {
            renderApply()
            return
        }
        val updated = declared.copy(stateOfHealthPercent = suggestion)
        VehicleSettings.write(this, updated)
        declared = updated
        applied = getString(
            R.string.battery_health_apply_done,
            ProvenanceText(this).render(estimate.stateOfHealthPercent, PATTERN_SOC),
        )
        renderHealth()
        renderApply()
    }

    companion object {
        private const val PATTERN_TREND = "%+.1f pt"
    }
}
