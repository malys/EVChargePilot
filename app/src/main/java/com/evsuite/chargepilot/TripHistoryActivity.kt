package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityTripHistoryBinding
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.model.AttributedEnergyEstimate
import com.evsuite.hardware.telemetry.model.EnergyAttribution
import com.evsuite.hardware.telemetry.model.EnergyAttributionCalculator
import com.evsuite.hardware.telemetry.model.EnergyAttributionResult
import com.evsuite.hardware.telemetry.model.ResidualAttribution
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Reverse-chronological trip ledger with one selected record kept open beside it. */
class TripHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripHistoryBinding
    private lateinit var store: EnergyTripHistoryStore
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-history")
    }
    private val adapter = TripAdapter()
    private var selectedStartedAtMs: Long? = null
    private var attributions: Map<Long, EnergyAttributionResult> = emptyMap()
    private var recorder: TripRecordingService? = null
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@TripHistoryActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                renderSpeedWhatIfGate()
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            renderSpeedWhatIfGate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            speedKmh = null
            speedObservedAtMs = null
            renderSpeedWhatIfGate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE))
        selectedStartedAtMs = savedInstanceState?.getLong(STATE_SELECTED)?.takeIf { it != 0L }

        binding.historyList.adapter = adapter
        binding.historyList.setOnItemClickListener { _, _, position, _ ->
            selectedStartedAtMs = adapter.getItem(position).summary.startedAtMs
            adapter.notifyDataSetChanged()
            renderSelected()
        }
        binding.backAction.setOnClickListener { finish() }
        binding.deleteAllAction.setOnClickListener {
            startActivity(TripDeleteConfirmationActivity.all(this))
        }
        binding.exportAllAction.setOnClickListener {
            startActivity(TripExportActivity.all(this))
        }
        binding.deleteTripAction.setOnClickListener {
            selectedStartedAtMs?.let { startedAt ->
                startActivity(TripDeleteConfirmationActivity.single(this, startedAt))
            }
        }
        binding.exportTripAction.setOnClickListener {
            selectedStartedAtMs?.let { startedAt ->
                startActivity(TripExportActivity.single(this, startedAt))
            }
        }
        binding.speedWhatIfAction.setOnClickListener { openSpeedWhatIfIfParked() }
        binding.energyBreakdownAction.setOnClickListener {
            selectedStartedAtMs?.let { startedAt ->
                startActivity(EnergyBreakdownActivity.forTrip(this, startedAt))
            }
        }
        renderSpeedWhatIfGate()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) renderSpeedWhatIfGate()
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedStartedAtMs?.let { outState.putLong(STATE_SELECTED, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        recorder?.clearListener(this)
        recorder = null
        binding.speedWhatIfAction.removeCallbacks(gateExpiry)
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

    private fun loadTrips() {
        disk.execute {
            val trips = store.read()
            val evidence = trips.firstNotNullOfOrNull { it.summary.batteryPowerEvidence }
            val model = LocalEnergyModel.loadOrTrain(filesDir, trips, evidence)
            val loadedAttributions = trips.associate { trip ->
                trip.summary.startedAtMs to EnergyAttributionCalculator.calculate(trip, model)
            }
            val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val rows = trips.map { trip -> buildRow(trip, dateFormatter) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                attributions = loadedAttributions
                adapter.submit(trips, rows)
                if (trips.none { it.summary.startedAtMs == selectedStartedAtMs }) {
                    selectedStartedAtMs = trips.firstOrNull()?.summary?.startedAtMs
                }
                adapter.notifyDataSetChanged()
                val empty = trips.isEmpty()
                binding.loadingState.visibility = View.GONE
                binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                binding.historyContent.visibility = if (empty) View.GONE else View.VISIBLE
                binding.deleteAllAction.isEnabled = !empty
                binding.exportAllAction.isEnabled = !empty
                binding.energyBreakdownAction.isEnabled = !empty
                renderSelected()
                renderSpeedWhatIfGate()
            }
        }
    }

    private fun renderSpeedWhatIfGate() {
        binding.speedWhatIfAction.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)
        binding.speedWhatIfAction.isEnabled = selectedStartedAtMs != null &&
            gate == ParkedDeletionGate.PARKED
        if (gate == ParkedDeletionGate.PARKED) {
            val ageMs = nowMs - checkNotNull(speedObservedAtMs)
            binding.speedWhatIfAction.postDelayed(
                gateExpiry,
                ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
            )
        }
    }

    private fun openSpeedWhatIfIfParked() {
        val gate = ParkedDeletionPolicy.gate(
            speedKmh,
            speedObservedAtMs,
            System.currentTimeMillis(),
        )
        val startedAtMs = selectedStartedAtMs
        if (gate != ParkedDeletionGate.PARKED || startedAtMs == null) {
            renderSpeedWhatIfGate()
            Snackbar.make(
                binding.root,
                if (gate == ParkedDeletionGate.MOVING) {
                    R.string.speed_what_if_moving
                } else {
                    R.string.speed_what_if_speed_unavailable
                },
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        startActivity(SpeedWhatIfActivity.forTrip(this, startedAtMs))
    }

    private fun renderSelected() {
        val trip = adapter.items.firstOrNull {
            it.summary.startedAtMs == selectedStartedAtMs
        } ?: return
        val summary = trip.summary
        binding.detailDate.text = formatDate(summary.startedAtMs)
        bindValue(binding.detailDuration, R.string.label_duration, duration(summary.durationMs))
        bindValue(
            binding.detailDistance,
            R.string.label_distance,
            summary.recordedDistanceKm?.let(::distance),
            R.string.trip_speed_not_recorded,
        )
        bindValue(
            binding.detailConsumption,
            R.string.label_consumption,
            summary.averageConsumptionKwhPer100Km?.let(::consumption),
            R.string.trip_power_not_recorded,
        )
        val soc = if (summary.startSocPercent != null && summary.endSocPercent != null) {
            String.format(
                Locale.getDefault(), "%.0f %% → %.0f %%",
                summary.startSocPercent, summary.endSocPercent,
            )
        } else null
        bindValue(binding.detailSoc, R.string.label_soc_change, soc)
        bindValue(
            binding.detailConsumed,
            R.string.label_energy_used,
            summary.consumedKwh?.let(::energy),
            R.string.trip_power_not_recorded,
        )
        bindValue(
            binding.detailRegenerated,
            R.string.label_regenerated,
            summary.regeneratedKwh?.let(::energy),
            R.string.trip_power_not_recorded,
        )
        renderAttribution(attributions[summary.startedAtMs])

        val samples = trip.samples
        val hasTrack = !samples.isNullOrEmpty()
        binding.trackGroup.visibility = if (hasTrack) View.VISIBLE else View.GONE
        binding.trackUnavailable.visibility = if (hasTrack) View.GONE else View.VISIBLE
        if (hasTrack) {
            binding.tripPlot.setSamples(samples!!)
            val minutes = ((samples.last().atMs - samples.first().atMs).coerceAtLeast(0L) / 60_000L)
            binding.tripPlot.contentDescription = getString(R.string.trip_track_description, minutes)
        }
    }

    private fun renderAttribution(result: EnergyAttributionResult?) {
        binding.detailAttribution.text = when (result) {
            is EnergyAttributionResult.Ready -> attributionText(result.attribution)
            is EnergyAttributionResult.Unavailable -> getString(
                R.string.trip_attribution_unavailable,
                getString(
                    when {
                        batteryPowerNeverPublished() -> R.string.reason_power_never_published
                        result.reason ==
                            com.evsuite.hardware.telemetry.UnavailableReason.MODEL_NOT_TRAINED ->
                            R.string.reason_model_not_trained
                        else -> R.string.reason_insufficient_samples
                    },
                ),
            )
            null -> getString(
                R.string.trip_attribution_unavailable,
                getString(
                    if (batteryPowerNeverPublished()) {
                        R.string.reason_power_never_published
                    } else {
                        R.string.reason_model_not_trained
                    }
                ),
            )
        }
        binding.detailAttribution.contentDescription = binding.detailAttribution.text
    }

    private fun attributionText(value: EnergyAttribution): String = buildString {
        appendLine(getString(R.string.trip_attribution_intro))
        appendLine(
            getString(
                R.string.trip_attribution_traction,
                band(value.modelledTraction),
            ),
        )
        value.residuals.forEach { appendLine(residualText(it)) }
        append(
            getString(
                R.string.trip_attribution_reconciliation,
                checkNotNull(value.measuredRegenerationKwh.value),
                checkNotNull(value.unmodelledDiscrepancyKwh.value),
                value.reconciliationErrorKwh,
            ),
        )
    }

    private fun residualText(value: ResidualAttribution): String {
        val context = getString(
            when (value.context) {
                ResidualContext.CLIMATE_ACTIVE -> R.string.trip_attribution_climate_active
                ResidualContext.CLIMATE_INACTIVE -> R.string.trip_attribution_climate_inactive
                ResidualContext.CLIMATE_UNKNOWN -> R.string.trip_attribution_climate_unknown
            },
        )
        return getString(
            when (value.finding) {
                ResidualFinding.DISTINGUISHABLE -> R.string.trip_attribution_residual
                ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO ->
                    R.string.trip_attribution_residual_indistinguishable
                ResidualFinding.NEGATIVE_MODEL_ERROR ->
                    R.string.trip_attribution_residual_negative
            },
            context,
            band(value.estimate),
        )
    }

    private fun band(value: AttributedEnergyEstimate): String = String.format(
        Locale.getDefault(),
        "%.2f–%.2f kWh",
        value.bandLowKwh,
        value.bandHighKwh,
    )

    /** Missing values explain themselves both to TalkBack and to a tap on the em dash. */
    private fun bindValue(
        view: TextView,
        labelRes: Int,
        rendered: String?,
        reasonRes: Int = R.string.trip_value_not_recorded,
    ) {
        val label = getString(labelRes)
        val target = view.parent as View
        target.setOnClickListener(null)
        target.isClickable = false
        target.isFocusable = false
        target.contentDescription = null
        target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        view.setOnClickListener(null)
        view.isClickable = false
        view.isFocusable = false
        if (rendered == null) {
            val reason = getString(reasonRes)
            view.text = DASH
            view.contentDescription = null
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            target.contentDescription = getString(
                R.string.trip_value_missing_description,
                label,
                reason,
            )
            target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            target.isFocusable = true
            target.setOnClickListener {
                Snackbar.make(binding.root, reason, Snackbar.LENGTH_LONG).show()
            }
        } else {
            view.text = rendered
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            view.contentDescription = getString(R.string.trip_value_description, label, rendered)
        }
    }

    private fun formatDate(atMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(atMs))

    /** Row text is prepared once off the UI thread so recycled views stay allocation-light. */
    private fun buildRow(trip: StoredTrip, dateFormatter: DateFormat): TripRow {
        val summary = trip.summary
        val average = summary.averageConsumptionKwhPer100Km?.let(::consumption) ?: DASH
        val soc = if (summary.startSocPercent != null && summary.endSocPercent != null) {
            String.format(
                Locale.getDefault(), "%.0f %% → %.0f %%",
                summary.startSocPercent, summary.endSocPercent,
            )
        } else DASH
        val date = dateFormatter.format(Date(summary.startedAtMs))
        val distance = summary.recordedDistanceKm?.let(::distance) ?: DASH
        val summaryText = getString(
            R.string.trip_history_row_summary,
            duration(summary.durationMs),
            distance,
        )
        val energyText = getString(R.string.trip_history_row_energy, average, soc)
        val description = buildString {
            append(date)
            append(". ")
            append(summaryText)
            append(". ")
            append(energyText)
            if (summary.averageConsumptionKwhPer100Km == null) {
                append(". ")
                append(getString(R.string.trip_power_not_recorded))
            }
            if (summary.recordedDistanceKm == null) {
                append(". ")
                append(getString(R.string.trip_speed_not_recorded))
            }
            if (summary.startSocPercent == null || summary.endSocPercent == null) {
                append(". ")
                append(getString(R.string.trip_value_not_recorded))
            }
        }
        return TripRow(date, summaryText, energyText, description)
    }

    private fun duration(milliseconds: Long): String {
        val totalMinutes = milliseconds / 60_000L
        return String.format(Locale.getDefault(), "%d:%02d", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun distance(value: Double): String = format("%.1f km", value)
    private fun energy(value: Double): String = format("%.2f kWh", value)
    private fun consumption(value: Double): String = format("%.1f kWh/100 km", value)
    private fun format(pattern: String, value: Double): String =
        String.format(Locale.getDefault(), pattern, value)

    private inner class TripAdapter : BaseAdapter() {
        var items: List<StoredTrip> = emptyList()
            private set
        private var rows: List<TripRow> = emptyList()

        fun submit(newItems: List<StoredTrip>, newRows: List<TripRow>) {
            require(newItems.size == newRows.size)
            items = newItems
            rows = newRows
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].summary.startedAtMs

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: RowHolder
            if (convertView == null) {
                view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_trip_history, parent, false)
                holder = RowHolder(
                    view.findViewById(R.id.tripRowDate),
                    view.findViewById(R.id.tripRowSummary),
                    view.findViewById(R.id.tripRowEnergy),
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as RowHolder
            }
            val summary = getItem(position).summary
            val row = rows[position]
            holder.date.text = row.date
            holder.summary.text = row.summary
            holder.energy.text = row.energy
            view.setBackgroundResource(
                if (summary.startedAtMs == selectedStartedAtMs) {
                    R.drawable.bg_history_row_selected
                } else {
                    android.R.color.transparent
                }
            )
            view.contentDescription = row.contentDescription
            return view
        }
    }

    private data class TripRow(
        val date: String,
        val summary: String,
        val energy: String,
        val contentDescription: String,
    )

    private data class RowHolder(
        val date: TextView,
        val summary: TextView,
        val energy: TextView,
    )

    private companion object {
        const val HISTORY_FILE = "trips.json"
        const val STATE_SELECTED = "selected_started_at"
        const val DASH = "—"
    }

    private val gateExpiry = Runnable { renderSpeedWhatIfGate() }
}
