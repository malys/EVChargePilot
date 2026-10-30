package com.evsuite.chargepilot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityAdviceHistoryBinding
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The trips' eco scores and hard braking as columns, then the latest tip of each kind (CP-087).
 *
 * The scores are the trips' own, from the same reviews the Trips page draws, not parsed back out
 * of stored sentences. The tips are the [AdviceJournal] as stored, so one reads as it was said.
 */
class AdviceHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdviceHistoryBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-advice-history")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdviceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        disk.execute {
            val entries = AdviceJournal.of(filesDir).read()
            val trips = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
                .sortedBy { it.summary.startedAtMs }
            val reviews = reviewHistory(filesDir, trips).reviews
            val recent = trips.takeLast(MAX_TRIPS)
            val scores = recent.map { trip ->
                val verdict = (reviews[trip.summary.startedAtMs] as? EcoTripReview.Ready)?.verdict
                EcoScore.of(trip.summary, verdict)?.total
            }
            val braking = recent.map { trip ->
                val km = trip.summary.recordedDistanceKm?.takeIf { it >= EcoScore.MIN_DISTANCE_KM }
                val count = trip.summary.harshBrakings
                if (km != null && count != null) count * PER_KM / km else null
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render(entries, scores, braking)
            }
        }
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun render(entries: List<AdviceEntry>, scores: List<Int?>, braking: List<Double?>) {
        val known = scores.filterNotNull()
        val average = known.takeIf { it.isNotEmpty() }?.average()
        binding.scoreColumns.show(
            scores.map { score ->
                ColumnChartView.Column(
                    score?.let { it / 100f },
                    if (score != null && average != null && score < average) R.color.ev_warn else R.color.ev_accent,
                )
            },
        )
        binding.scoreLegend.text = average?.let {
            getString(R.string.advice_scores_legend, known.size, it.toInt())
        } ?: getString(R.string.value_unavailable)

        val rates = braking.filterNotNull()
        val highest = rates.maxOrNull()?.takeIf { it > 0.0 }
        binding.brakingColumns.show(
            braking.map { rate ->
                ColumnChartView.Column(highest?.let { top -> rate?.let { (it / top).toFloat() } }, R.color.ev_warn)
            },
        )
        binding.brakingLegend.text = if (rates.isEmpty()) {
            getString(R.string.value_unavailable)
        } else {
            getString(R.string.advice_braking_legend, rates.average(), rates.max())
        }

        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun latest(kinds: Set<AdviceKind>) = entries.lastOrNull { it.kind in kinds }
        latest(DRIVING_KINDS).let {
            binding.drivingDate.text = it?.let { entry -> format.format(Date(entry.atMs)) }.orEmpty()
            binding.drivingTip.text = it?.text ?: getString(R.string.advice_none)
        }
        latest(CHARGE_KINDS).let {
            binding.chargeDate.text = it?.let { entry -> format.format(Date(entry.atMs)) }.orEmpty()
            binding.chargeTip.text = it?.text ?: getString(R.string.advice_none)
        }
    }

    private companion object {
        const val HISTORY_FILE = "trips.json"
        const val MAX_TRIPS = 15
        const val PER_KM = 10.0
        val DRIVING_KINDS = setOf(AdviceKind.TRIP_END, AdviceKind.DRIVING)
        val CHARGE_KINDS = setOf(AdviceKind.BATTERY, AdviceKind.PARKED, AdviceKind.CHARGING)
    }
}
