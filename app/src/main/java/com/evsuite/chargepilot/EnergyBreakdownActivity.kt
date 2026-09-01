package com.evsuite.chargepilot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityEnergyBreakdownBinding
import com.evsuite.chargepilot.databinding.RowEnergyBreakdownBinding
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.model.AttributedEnergyEstimate
import com.evsuite.hardware.telemetry.model.EnergyAttribution
import com.evsuite.hardware.telemetry.model.EnergyAttributionCalculator
import com.evsuite.hardware.telemetry.model.EnergyAttributionResult
import com.evsuite.hardware.telemetry.model.ResidualAttribution
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Complete read-only post-trip reconciliation; unsupported consumer categories never appear. */
class EnergyBreakdownActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnergyBreakdownBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-energy-breakdown")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnergyBreakdownBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        val startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
        if (startedAtMs == INVALID_ID) {
            finish()
            return
        }
        load(startedAtMs)
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun load(startedAtMs: Long) {
        disk.execute {
            val trips = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
            val trip = trips.firstOrNull { it.summary.startedAtMs == startedAtMs }
            val evidence = trip?.summary?.batteryPowerEvidence
            val model = LocalEnergyModel.loadOrTrain(filesDir, trips, evidence)
            val result = trip?.let { EnergyAttributionCalculator.calculate(it, model) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(result)
            }
        }
    }

    private fun render(result: EnergyAttributionResult?) {
        binding.loadingState.visibility = View.GONE
        if (result !is EnergyAttributionResult.Ready) {
            binding.breakdownContent.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyReason.setText(
                if (result is EnergyAttributionResult.Unavailable &&
                    result.reason == com.evsuite.hardware.telemetry.UnavailableReason.INSUFFICIENT_SAMPLES
                ) {
                    R.string.energy_breakdown_missing_power
                } else {
                    R.string.energy_breakdown_model_unavailable
                },
            )
            return
        }
        binding.emptyState.visibility = View.GONE
        binding.breakdownContent.visibility = View.VISIBLE
        renderReady(result.attribution)
    }

    private fun renderReady(value: EnergyAttribution) {
        binding.breakdownSummary.text = summary(value.residuals)
        binding.measuredRows.removeAllViews()
        binding.estimatedRows.removeAllViews()
        addRow(
            binding.measuredRows,
            R.string.energy_breakdown_consumed,
            R.string.energy_breakdown_claim_derived_power,
            energy(checkNotNull(value.totalConsumedKwh.value)),
        )
        addRow(
            binding.measuredRows,
            R.string.energy_breakdown_regenerated,
            R.string.energy_breakdown_claim_derived_power,
            energy(checkNotNull(value.measuredRegenerationKwh.value)),
        )
        addRow(
            binding.measuredRows,
            R.string.energy_breakdown_net,
            R.string.energy_breakdown_claim_derived_net,
            energy(checkNotNull(value.netBatteryEnergyKwh.value)),
        )
        addRow(
            binding.measuredRows,
            R.string.energy_breakdown_discrepancy,
            R.string.energy_breakdown_claim_discrepancy,
            energy(checkNotNull(value.unmodelledDiscrepancyKwh.value)),
        )

        addRow(
            binding.estimatedRows,
            R.string.energy_breakdown_traction,
            R.string.energy_breakdown_claim_estimated,
            band(value.modelledTraction),
        )
        value.residuals.forEach { residual ->
            addRow(
                binding.estimatedRows,
                residualLabel(residual.context),
                R.string.energy_breakdown_claim_residual,
                residualValue(residual),
            )
        }
        binding.reconciliation.text = getString(
            R.string.energy_breakdown_reconciliation,
            value.reconciliationErrorKwh,
        )
    }

    private fun addRow(container: LinearLayout, label: Int, claim: Int, value: String) {
        val row = RowEnergyBreakdownBinding.inflate(layoutInflater, container, false)
        row.rowLabel.setText(label)
        row.rowClaim.setText(claim)
        row.rowValue.text = value
        row.root.contentDescription = getString(
            R.string.energy_breakdown_row_description,
            getString(label),
            getString(claim),
            value,
        )
        container.addView(row.root)
    }

    private fun residualLabel(context: ResidualContext): Int = when (context) {
        ResidualContext.CLIMATE_ACTIVE -> R.string.energy_breakdown_residual_active
        ResidualContext.CLIMATE_INACTIVE -> R.string.energy_breakdown_residual_inactive
        ResidualContext.CLIMATE_UNKNOWN -> R.string.energy_breakdown_residual_unknown
    }

    private fun residualValue(value: ResidualAttribution): String = when (value.finding) {
        ResidualFinding.DISTINGUISHABLE -> band(value.estimate)
        ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO -> getString(
            R.string.energy_breakdown_indistinguishable,
            band(value.estimate),
        )
        ResidualFinding.NEGATIVE_MODEL_ERROR -> getString(
            R.string.energy_breakdown_negative_error,
            band(value.estimate),
        )
    }

    private fun summary(residuals: List<ResidualAttribution>): String = when {
        residuals.any { it.finding == ResidualFinding.NEGATIVE_MODEL_ERROR } ->
            getString(R.string.energy_breakdown_summary_negative)
        residuals.none { it.finding == ResidualFinding.DISTINGUISHABLE } ->
            getString(R.string.energy_breakdown_summary_noise)
        residuals.any {
            it.context == ResidualContext.CLIMATE_ACTIVE &&
                it.finding == ResidualFinding.DISTINGUISHABLE
        } -> getString(R.string.energy_breakdown_summary_climate)
        else -> getString(R.string.energy_breakdown_summary_other)
    }

    private fun energy(value: Double): String =
        String.format(Locale.getDefault(), "%.2f kWh", value)

    private fun band(value: AttributedEnergyEstimate): String = String.format(
        Locale.getDefault(),
        "≈ %.2f–%.2f kWh",
        value.bandLowKwh,
        value.bandHighKwh,
    )

    companion object {
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE
        private const val HISTORY_FILE = "trips.json"

        fun forTrip(context: Context, startedAtMs: Long) =
            Intent(context, EnergyBreakdownActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)
    }
}
