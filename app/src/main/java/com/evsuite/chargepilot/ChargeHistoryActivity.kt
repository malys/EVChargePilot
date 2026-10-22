package com.evsuite.chargepilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.evsuite.chargepilot.databinding.ActivityChargeHistoryBinding
import com.evsuite.chargepilot.databinding.ItemChargeBandBinding
import com.evsuite.chargepilot.databinding.ItemChargeRowBinding
import com.evsuite.hardware.telemetry.ChargeEnergy
import com.evsuite.hardware.telemetry.ChargeEnergyReport
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Every charge the battery ledger holds, and what the watched ones measured.
 *
 * The battery page has room for one line about charging; this screen is where the list lives.
 * Nothing new is sampled or inferred: rows come from [ChargeEnergyReport], the same report the
 * battery page and the diagnostic bundle read, so the three can never disagree.
 *
 * A charge the head unit slept through has no measured energy: the integral did not move while
 * nobody was integrating. It shows an estimate instead ([ChargeEnergyEstimate]: points × declared
 * capacity, power over the span), written as estimated, never in the measured line's words.
 */
class ChargeHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargeHistoryBinding
    private var pack = VehicleSettings.Values().pack
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-charge-history")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChargeHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        disk.execute {
            val settings = VehicleSettings.read(this)
            val report = LocalBatteryLearning.refresh(filesDir, settings.usableCapacityKwhWhenNew)
                .charge
            runOnUiThread {
                pack = settings.pack
                if (!isFinishing && !isDestroyed) render(report)
            }
        }
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun render(report: ChargeEnergyReport) {
        val charges = report.pluggedCharges
        binding.chargeRows.removeAllViews()
        binding.curveBands.removeAllViews()
        if (charges.isEmpty()) {
            binding.analysisFacts.text = getString(R.string.charge_history_empty)
            binding.curveFacts.text = getString(R.string.charge_history_curve_empty)
            binding.chargeNotes.text = ""
            return
        }
        renderAnalysis(charges)
        renderColumns(charges)
        renderCurve(report)
        charges.asReversed().take(MAX_ROWS).forEach(::addRow)
        val notes = ArrayList<String>()
        if (charges.any { ChargeEnergyEstimate.of(it, pack)?.measured == false }) {
            notes += getString(R.string.charge_history_estimated_note, pack.usableCapacityKwhWhenNew)
        }
        if (charges.any { it.minOutsideTempCelsius != null }) {
            notes += getString(R.string.charge_history_temperature)
        }
        binding.chargeNotes.text = notes.joinToString("\n\n")
    }

    private fun renderAnalysis(charges: List<ChargeEnergy>) {
        val median = charges.sortedBy { it.session.gainedPercent }[charges.size / 2].session
        binding.countValue.text = format("%d", charges.size)
        binding.addedValue.text = format("+%.0f", charges.sumOf { it.session.gainedPercent })
        binding.medianValue.text = format("+%.0f", median.gainedPercent)
        val lines = ArrayList<String>()
        lines += getString(
            R.string.charge_history_summary,
            charges.count { it.watched },
            median.meanPercentPerHour,
            charges.maxOf { it.session.peakPercentPerHour },
        )
        val watched = charges.filter { it.watched && it.packDeltaKwh != null }
        if (watched.isNotEmpty()) {
            val energy = watched.sumOf { abs(it.packDeltaKwh!!) }
            val hours = watched.sumOf { it.session.durationHours }
            lines += getString(
                R.string.charge_history_energy,
                energy,
                if (hours > 0.0) energy / hours else 0.0,
                watched.mapNotNull { it.peakPowerKw }.maxOrNull() ?: 0.0,
            )
        }
        val estimated = charges.mapNotNull { ChargeEnergyEstimate.of(it, pack) }.filter { !it.measured }
        if (estimated.isNotEmpty()) {
            lines += getString(
                R.string.charge_history_energy_estimated,
                estimated.size,
                estimated.sumOf { it.kwh },
            )
        }
        binding.analysisFacts.text = lines.joinToString("\n")
    }

    /** Each charge's energy as a column, measured solid and estimated light; unknown is a gap. */
    private fun renderColumns(charges: List<ChargeEnergy>) {
        val shown = charges.takeLast(MAX_COLUMNS)
        val estimates = shown.map { ChargeEnergyEstimate.of(it, pack) }
        val tallest = estimates.maxOfOrNull { it?.kwh ?: 0.0 }?.takeIf { it > 0.0 }
        if (tallest == null) {
            binding.chargeColumns.show(emptyList())
            binding.chargeColumnsLegend.text = ""
            return
        }
        binding.chargeColumns.show(
            estimates.map { estimate ->
                ColumnChartView.Column(
                    estimate?.let { (it.kwh / tallest).toFloat() },
                    R.color.ev_accent,
                    if (estimate?.measured == true) BarGaugeView.OPAQUE else BarGaugeView.LIGHT,
                )
            },
        )
        binding.chargeColumnsLegend.text = getString(R.string.charge_history_columns_legend, tallest)
    }

    private fun renderCurve(report: ChargeEnergyReport) {
        val bands = report.powerBySocBand()
        if (bands.isEmpty()) {
            binding.curveFacts.text = getString(R.string.charge_history_curve_empty)
            return
        }
        binding.curveFacts.text = ""
        val strongest = bands.maxOf { it.meanPowerKw }.takeIf { it > 0.0 } ?: 1.0
        bands.forEach {
            val band = ItemChargeBandBinding.inflate(layoutInflater, binding.curveBands, true)
            band.bandRange.text =
                getString(R.string.charge_history_band_range, it.fromPercent, it.toPercent)
            band.bandPower.text =
                getString(R.string.charge_history_band_power, it.meanPowerKw, it.hours)
            band.bandBar.show(
                listOf(BarGaugeView.Span(0f, (it.meanPowerKw / strongest).toFloat(), R.color.ev_accent)),
            )
        }
    }

    private fun addRow(charge: ChargeEnergy) {
        val session = charge.session
        val row = ItemChargeRowBinding.inflate(layoutInflater, binding.chargeRows, true)
        row.rowDate.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(session.startedAtMs))
        val low = charge.minOutsideTempCelsius
        val high = charge.maxOutsideTempCelsius
        row.rowLevels.text = if (low != null && high != null) {
            getString(
                R.string.charge_history_row_levels_temperature,
                session.startSocPercent, session.endSocPercent, low, high,
            )
        } else {
            getString(R.string.charge_history_row_levels, session.startSocPercent, session.endSocPercent)
        }
        row.rowBar.show(
            listOf(
                BarGaugeView.Span(
                    (session.startSocPercent / 100.0).toFloat(),
                    (session.endSocPercent / 100.0).toFloat(),
                    R.color.ev_accent,
                ),
            ),
        )
        row.rowGain.text =
            getString(R.string.charge_history_row_gain, session.gainedPercent, session.durationHours)
        val estimate = ChargeEnergyEstimate.of(charge, pack)
        row.rowEnergy.text = when {
            estimate == null -> getString(R.string.charge_history_partial)
            estimate.measured ->
                getString(R.string.charge_history_row_energy, estimate.kwh, estimate.powerKw ?: 0.0)
            estimate.powerKw != null ->
                getString(R.string.charge_history_row_estimated, estimate.kwh, estimate.powerKw)
            else -> getString(R.string.charge_history_row_estimated_energy, estimate.kwh)
        }
        row.rowEnergy.setTextColor(
            ContextCompat.getColor(this, if (estimate == null) R.color.ev_warn else R.color.ev_text_secondary),
        )
    }

    private fun format(pattern: String, vararg args: Any) =
        String.format(Locale.getDefault(), pattern, *args)

    private companion object {
        /** The ledger is bounded already; a list longer than this is scrolled, not read. */
        const val MAX_ROWS = 50

        /** About as many columns as stay wider than a finger on the left pane. */
        const val MAX_COLUMNS = 30
    }
}
