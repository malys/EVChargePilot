package com.evsuite.chargepilot

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityAdviceHistoryBinding
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.StoredTrip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The trips' eco scores and hard braking as columns, then the latest tip of each kind (CP-087).
 *
 * The scores are the trips' own, from the same reviews the Trips page draws, not parsed back out
 * of stored sentences. The tips are the [AdviceJournal] as stored, so one reads as it was said.
 * The columns can be narrowed to one driver, the figure for everyone kept beside theirs; the tips
 * stay everyone's, since the journal does not say whose trip a sentence was about.
 */
class AdviceHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdviceHistoryBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-advice-history")
    }
    private var entries: List<AdviceEntry> = emptyList()
    private var trips: List<StoredTrip> = emptyList()
    private var reviews: Map<Long, EcoTripReview> = emptyMap()
    private var drivers: Map<Long, String> = emptyMap()
    private var driverFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdviceHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.driverFilterAction.setOnClickListener { chooseDriverFilter() }
        disk.execute {
            val entries = AdviceJournal.of(filesDir).read()
            val trips = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
                .sortedBy { it.summary.startedAtMs }
            val reviews = reviewHistory(filesDir, trips).reviews
            val drivers = TripDriverStore(File(filesDir, TripDriverStore.FILE_NAME)).read()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                this.entries = entries
                this.trips = trips
                this.reviews = reviews
                this.drivers = drivers
                binding.driverFilterAction.visibility = if (drivers.isEmpty()) View.GONE else View.VISIBLE
                render()
            }
        }
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun score(trip: StoredTrip): Int? {
        val verdict = (reviews[trip.summary.startedAtMs] as? EcoTripReview.Ready)?.verdict
        return EcoScore.of(trip.summary, verdict)?.total
    }

    private fun braking(trip: StoredTrip): Double? {
        val km = trip.summary.recordedDistanceKm?.takeIf { it >= EcoScore.MIN_DISTANCE_KM }
        val count = trip.summary.harshBrakings
        return if (km != null && count != null) count * PER_KM / km else null
    }

    private fun render() {
        val recent = trips
            .filter { DriverAttribution.matches(drivers, it.summary.startedAtMs, driverFilter) }
            .takeLast(MAX_TRIPS)
        val scores = recent.map(::score)
        val braking = recent.map(::braking)
        // Everyone's figures, said after a driver's so the two can be compared at a glance.
        val everyone = trips.takeLast(MAX_TRIPS).takeIf { driverFilter != null }
        val everyoneScore = everyone?.mapNotNull(::score)?.takeIf { it.isNotEmpty() }?.average()
        val everyoneBraking = everyone?.mapNotNull(::braking)?.takeIf { it.isNotEmpty() }?.average()
        binding.driverFilterAction.text = driverLabel(driverFilter)

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
        binding.scoreLegend.text = listOfNotNull(
            average?.let { getString(R.string.advice_scores_legend, known.size, it.toInt()) }
                ?: getString(R.string.value_unavailable),
            everyoneScore?.let { getString(R.string.advice_scores_all, it.toInt()) },
        ).joinToString(" · ")

        val rates = braking.filterNotNull()
        val highest = rates.maxOrNull()?.takeIf { it > 0.0 }
        binding.brakingColumns.show(
            braking.map { rate ->
                ColumnChartView.Column(highest?.let { top -> rate?.let { (it / top).toFloat() } }, R.color.ev_warn)
            },
        )
        binding.brakingLegend.text = listOfNotNull(
            if (rates.isEmpty()) {
                getString(R.string.value_unavailable)
            } else {
                getString(R.string.advice_braking_legend, rates.average(), rates.max())
            },
            everyoneBraking?.let { getString(R.string.advice_braking_all, it) },
        ).joinToString(" · ")

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

    private fun driverLabel(driver: String?): String = when (driver) {
        null -> getString(R.string.driver_all)
        DriverAttribution.UNKNOWN -> getString(R.string.driver_unknown)
        else -> driver
    }

    /** The same choices as the Trips page's filter: everyone, each named driver, nobody's. */
    private fun chooseDriverFilter() {
        val choices = listOf<String?>(null) + drivers.values.distinct().sorted() + DriverAttribution.UNKNOWN
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.driver_filter_title)
            .setSingleChoiceItems(
                choices.map(::driverLabel).toTypedArray(),
                choices.indexOf(driverFilter).coerceAtLeast(0),
            ) { dialog, which ->
                driverFilter = choices[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private companion object {
        const val HISTORY_FILE = "trips.json"
        const val MAX_TRIPS = 15
        const val PER_KM = 10.0
        val DRIVING_KINDS = setOf(AdviceKind.TRIP_END, AdviceKind.DRIVING)
        val CHARGE_KINDS = setOf(AdviceKind.BATTERY, AdviceKind.PARKED, AdviceKind.CHARGING)
    }
}
