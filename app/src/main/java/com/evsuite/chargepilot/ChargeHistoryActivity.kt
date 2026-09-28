package com.evsuite.chargepilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityChargeHistoryBinding
import com.evsuite.hardware.telemetry.ChargeEnergy
import com.evsuite.hardware.telemetry.ChargeEnergyReport
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Every charge the battery ledger holds, and what the watched ones measured.
 *
 * The battery page has room for one line about charging; this screen is where the list lives.
 * Nothing new is sampled or inferred: rows come from [ChargeEnergyReport], the same report the
 * battery page and the diagnostic bundle read, so the three can never disagree. There is no
 * cost here on purpose — a tariff is a declaration, and every figure on this screen is measured.
 *
 * A charge the head unit slept through keeps its points and its span and shows no energy or
 * power: the integral did not move while nobody was integrating, and a zero would be a lie.
 */
class ChargeHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChargeHistoryBinding
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
        if (charges.isEmpty()) {
            binding.analysisFacts.text = getString(R.string.charge_history_empty)
            binding.curveFacts.text = getString(R.string.charge_history_curve_empty)
            binding.chargeList.text = ""
            return
        }
        binding.analysisFacts.text = analysis(report, charges).joinToString("\n\n")
        val bands = report.powerBySocBand()
        binding.curveFacts.text = if (bands.isEmpty()) {
            getString(R.string.charge_history_curve_empty)
        } else {
            bands.joinToString("\n") {
                getString(
                    R.string.charge_history_band,
                    it.fromPercent, it.toPercent, it.meanPowerKw, it.hours,
                )
            }
        }
        binding.chargeList.text = charges.asReversed()
            .take(MAX_ROWS)
            .joinToString("\n\n") { row(it) }
    }

    private fun analysis(report: ChargeEnergyReport, charges: List<ChargeEnergy>): List<String> {
        val lines = ArrayList<String>()
        lines += getString(
            R.string.charge_history_summary,
            charges.size,
            charges.count { it.watched },
            charges.sumOf { it.session.gainedPercent },
        )
        val median = charges.sortedBy { it.session.gainedPercent }[charges.size / 2].session
        lines += getString(
            R.string.charge_history_typical,
            median.gainedPercent,
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
        lines += getString(
            R.string.charge_history_verdicts,
            report.packSign.name,
            report.counterBehaviour.name,
            report.chargingStatuses.joinToString(",").ifEmpty { "—" },
        )
        lines += getString(R.string.charge_history_temperature)
        return lines
    }

    private fun row(charge: ChargeEnergy): String {
        val session = charge.session
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val lines = ArrayList<String>()
        lines += getString(
            R.string.charge_history_row,
            format.format(Date(session.startedAtMs)),
            format.format(Date(session.endedAtMs)),
            session.startSocPercent,
            session.endSocPercent,
            session.gainedPercent,
            session.durationHours,
            session.meanPercentPerHour,
        )
        val energy = charge.packDeltaKwh?.takeIf { charge.watched }
        val power = charge.meanPowerKw
        lines += if (energy != null && power != null) {
            getString(
                R.string.charge_history_row_watched,
                abs(energy),
                power,
                charge.peakPowerKw ?: power,
            )
        } else {
            getString(R.string.charge_history_row_unwatched)
        }
        val low = charge.minOutsideTempCelsius
        val high = charge.maxOutsideTempCelsius
        if (low != null && high != null) {
            lines += getString(R.string.charge_history_row_temperature, low, high)
        }
        return lines.joinToString("\n")
    }

    private companion object {
        /** The ledger is bounded already; a list longer than this is scrolled, not read. */
        const val MAX_ROWS = 50
    }
}
