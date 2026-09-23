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
import com.evsuite.chargepilot.databinding.ActivityTripHistoryBinding
import com.evsuite.chargepilot.databinding.ViewTripOverviewCardBinding
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.model.AttributedEnergyEstimate
import com.evsuite.hardware.telemetry.model.EnergyAttribution
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two views of the trips: an overview after Tesla's energy app — consumption over the last
 * kilometres, the range it implies, four groups of trips — and the reverse-chronological
 * ledger with one selected record kept open beside it.
 */
class TripHistoryActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.TRIPS

    private lateinit var binding: ActivityTripHistoryBinding
    private lateinit var store: EnergyTripHistoryStore
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-history")
    }
    private val adapter = TripAdapter()
    private var selectedStartedAtMs: Long? = null
    private var attributions: Map<Long, EnergyAttributionResult> = emptyMap()
    private var reviews: Map<Long, EcoTripReview> = emptyMap()
    private val reasons by lazy { ProvenanceText(this) }
    private var recorder: TripRecordingService? = null
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null
    private var bound = false
    private val overviewPrefs by lazy { getSharedPreferences(OVERVIEW_FILE, MODE_PRIVATE) }
    private var showOverview = true
    private var loaded = false
    private var lastOverviewMs = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@TripHistoryActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                renderSpeedWhatIfGate()
                if (showOverview && snapshot.timestampMs - lastOverviewMs >= OVERVIEW_REFRESH_MS) {
                    loadOverview()
                }
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            renderSpeedWhatIfGate()
            // The range needs the pack's charge, which only the service has.
            loadOverview()
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
        showOverview = savedInstanceState?.getBoolean(STATE_OVERVIEW) ?: true

        binding.viewChoice.check(if (showOverview) R.id.viewOverview else R.id.viewLedger)
        binding.viewChoice.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            showOverview = id == R.id.viewOverview
            renderMode()
            if (showOverview) loadOverview()
        }
        binding.windowChoice.check(WINDOW_BUTTONS[selectedWindowKm()] ?: R.id.window150)
        binding.windowChoice.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val km = WINDOW_BUTTONS.entries.first { it.value == id }.key
            overviewPrefs.edit().putFloat(KEY_WINDOW, km.toFloat()).apply()
            loadOverview()
        }
        binding.resetTripAAction.setOnClickListener { resetTripAIfParked() }
        listOf(
            binding.overviewTrace,
            binding.cardCurrent.cardTrace,
            binding.cardSinceCharge.cardTrace,
            binding.cardTripA.cardTrace,
            binding.cardHistory.cardTrace,
        ).forEach(ChartFullScreen::install)

        binding.historyList.adapter = adapter
        binding.historyList.setOnItemClickListener { _, _, position, _ ->
            selectedStartedAtMs = adapter.getItem(position).summary.startedAtMs
            adapter.notifyDataSetChanged()
            renderSelected()
        }
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
        // The thumbnail is a few hundred pixels tall on the car and the first thing a shorter
        // panel takes space from. Reading a value off it is the full-size plot's job.
        binding.tripPlot.setOnClickListener {
            selectedStartedAtMs?.let { startedAt ->
                startActivity(TripPlotActivity.forTrip(this, startedAt))
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
        outState.putBoolean(STATE_OVERVIEW, showOverview)
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
            val reviewed = reviewHistory(filesDir, trips)
            val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val rows = trips.map { trip -> buildRow(trip, dateFormatter) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                attributions = reviewed.attributions
                reviews = reviewed.reviews
                adapter.submit(trips, rows)
                if (trips.none { it.summary.startedAtMs == selectedStartedAtMs }) {
                    selectedStartedAtMs = trips.firstOrNull()?.summary?.startedAtMs
                }
                adapter.notifyDataSetChanged()
                val empty = trips.isEmpty()
                loaded = true
                renderMode()
                loadOverview()
                binding.deleteAllAction.isEnabled = !empty
                binding.exportAllAction.isEnabled = !empty
                binding.energyBreakdownAction.isEnabled = !empty
                renderSelected()
                renderSpeedWhatIfGate()
            }
        }
    }

    /** Which of the two views, and the top-bar actions that belong to it, are on screen. */
    private fun renderMode() {
        val empty = adapter.items.isEmpty()
        binding.loadingState.visibility = if (loaded) View.GONE else View.VISIBLE
        binding.overviewContent.visibility = if (loaded && showOverview) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (loaded && !showOverview && empty) View.VISIBLE else View.GONE
        binding.historyContent.visibility =
            if (loaded && !showOverview && !empty) View.VISIBLE else View.GONE
        binding.windowChoice.visibility = if (showOverview) View.VISIBLE else View.INVISIBLE
        binding.resetTripAAction.visibility = if (showOverview) View.VISIBLE else View.GONE
        binding.exportAllAction.visibility = if (showOverview) View.GONE else View.VISIBLE
        binding.deleteAllAction.visibility = if (showOverview) View.GONE else View.VISIBLE
    }

    private fun selectedWindowKm(): Double =
        overviewPrefs.getFloat(KEY_WINDOW, DEFAULT_WINDOW_KM.toFloat()).toDouble()
            .takeIf { it in WINDOW_BUTTONS } ?: DEFAULT_WINDOW_KM

    private fun loadOverview() {
        if (!loaded) return
        lastOverviewMs = System.currentTimeMillis()
        val trips = adapter.items
        val latest = recorder?.latest
        val windowKm = selectedWindowKm()
        val tripAFromMs = overviewPrefs.getLong(KEY_TRIP_A, 0L)
        disk.execute {
            val value = buildOverview(trips, latest, windowKm, tripAFromMs)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderOverview(value)
            }
        }
    }

    /** Off the UI thread: the whole kept history is walked for the last card's trace. */
    private fun buildOverview(
        stored: List<StoredTrip>,
        latest: EnergySnapshot?,
        windowKm: Double,
        tripAFromMs: Long,
    ): Overview {
        val settings = VehicleSettings.read(this)
        val current = EnergyTripSession.current(System.currentTimeMillis())
        val currentTrip = current?.let { StoredTrip(it) }
        val all = listOfNotNull(currentTrip) + stored
        val window = TripOverview.window(all, windowKm)
        val consumption = TripOverview.consumption(window)
        val energyKwh = latest?.batteryEnergyKwh?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: settings.pack.energyAtSocKwh(latest?.socPercent?.toDouble()).value
        val rangeKm = if (energyKwh != null && consumption != null && consumption > 0.0) {
            energyKwh * 100.0 / consumption
        } else null
        val sinceCharge = TripOverview.sinceCharge(
            latest?.socPercent,
            current,
            stored.map { it.summary },
        )?.let { trips -> all.take(trips.size) }
        return Overview(
            windowKm = windowKm,
            coveredKm = window.sumOf { it.distanceKm },
            netKwh = window.sumOf { it.netKwh },
            consumption = consumption,
            rangeKm = rangeKm,
            reference = settings.referenceConsumptionKwhPer100Km,
            trace = TripOverview.trace(window),
            tripAFromMs = tripAFromMs,
            current = currentTrip?.let { card(listOf(it)) },
            sinceCharge = sinceCharge?.let(::card),
            tripA = card(all.filter { it.summary.startedAtMs >= tripAFromMs }),
            history = card(all),
        )
    }

    private fun card(tripsNewestFirst: List<StoredTrip>): OverviewCard {
        val segments = tripsNewestFirst.asReversed().flatMap(TripOverview::segments)
        return OverviewCard(
            totals = TripOverview.totals(tripsNewestFirst.map { it.summary }),
            trace = TripOverview.trace(segments),
        )
    }

    private fun renderOverview(value: Overview) {
        val window = format("%.0f km", value.windowKm)
        binding.overviewRange.text = getString(
            R.string.trip_overview_range,
            value.rangeKm?.let { format("%.0f km", it) } ?: DASH,
        )
        binding.overviewRangeBasis.text = getString(R.string.trip_overview_range_basis, window)
        binding.overviewConsumption.text = value.consumption?.let(::consumption) ?: DASH
        val delta = value.consumption?.minus(value.reference)
        binding.overviewDelta.text = delta?.let {
            getString(
                if (it > 0.0) R.string.trip_overview_above else R.string.trip_overview_below,
                consumption(abs(it)),
            )
        }.orEmpty()
        binding.overviewDelta.setTextColor(
            getColor(if ((delta ?: 0.0) > 0.0) R.color.ev_warn else R.color.ev_accent)
        )
        binding.overviewWindowEnergy.text = if (value.consumption == null) {
            getString(R.string.trip_overview_no_driving)
        } else {
            getString(
                R.string.trip_overview_window_energy,
                energy(value.netKwh),
                distance(value.coveredKm),
            )
        }
        binding.overviewLegend.text =
            getString(R.string.trip_overview_legend, consumption(value.reference))
        binding.overviewTrace.setTrace(value.trace, value.reference, value.coveredKm)
        binding.overviewTrace.contentDescription =
            getString(R.string.trip_overview_trace_description, distance(value.coveredKm))

        renderCard(
            binding.cardCurrent,
            getString(R.string.trip_card_current),
            value.current,
            R.string.trip_card_not_recording,
            value.reference,
        )
        renderCard(
            binding.cardSinceCharge,
            getString(R.string.trip_card_since_charge),
            value.sinceCharge,
            if (value.sinceCharge == null) R.string.trip_card_charge_unknown else R.string.trip_card_empty,
            value.reference,
        )
        renderCard(
            binding.cardTripA,
            if (value.tripAFromMs > 0L) {
                getString(R.string.trip_card_trip_a_since, formatDate(value.tripAFromMs))
            } else {
                getString(R.string.trip_card_trip_a)
            },
            value.tripA,
            R.string.trip_card_empty,
            value.reference,
        )
        renderCard(
            binding.cardHistory,
            getString(R.string.trip_card_history),
            value.history,
            R.string.trip_card_empty,
            value.reference,
        )
    }

    /** A missing group says why in place of its graph; its figures stay em dashes. */
    private fun renderCard(
        card: ViewTripOverviewCardBinding,
        title: String,
        value: OverviewCard?,
        reasonRes: Int,
        reference: Double,
    ) {
        card.cardTitle.text = title
        val totals = value?.totals?.takeIf { it.trips > 0 }
        card.cardReason.visibility = if (totals == null) View.VISIBLE else View.GONE
        card.cardReason.text = getString(reasonRes)
        card.cardTrace.setTrace(value?.trace.orEmpty(), reference, totals?.distanceKm)
        card.cardTrace.contentDescription = title
        card.cardEnergy.text = listOf(
            totals?.consumptionKwhPer100Km?.let(::consumption) ?: DASH,
            totals?.netKwh?.let(::energy) ?: DASH,
        ).joinToString("\n")
        card.cardDistance.text = listOf(
            totals?.distanceKm?.let(::distance) ?: DASH,
            totals?.let { duration(it.durationMs) } ?: DASH,
        ).joinToString("\n")
    }

    /** Parked only, like every other control on this page; one Undo, because a lost start is lost. */
    private fun resetTripAIfParked() {
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, System.currentTimeMillis())
        if (gate != ParkedDeletionGate.PARKED) {
            renderSpeedWhatIfGate()
            Snackbar.make(binding.root, R.string.trip_a_reset_moving, Snackbar.LENGTH_LONG).show()
            return
        }
        val previous = overviewPrefs.getLong(KEY_TRIP_A, 0L)
        overviewPrefs.edit().putLong(KEY_TRIP_A, System.currentTimeMillis()).apply()
        loadOverview()
        Snackbar.make(binding.root, R.string.trip_a_reset_done, Snackbar.LENGTH_LONG)
            .setAction(R.string.trip_a_reset_undo) {
                overviewPrefs.edit().putLong(KEY_TRIP_A, previous).apply()
                loadOverview()
            }
            .show()
    }

    private fun renderSpeedWhatIfGate() {
        binding.speedWhatIfAction.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)
        binding.resetTripAAction.isEnabled = gate == ParkedDeletionGate.PARKED
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
        renderEcoReview(reviews[summary.startedAtMs])

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

    /**
     * The verdict and what the drive's own numbers say would have changed it.
     *
     * A review that has not been computed yet and one that had nothing to measure read the same
     * to the driver, and both say which silence it was rather than showing an empty line.
     */
    private fun renderEcoReview(review: EcoTripReview?) {
        val text = when (review) {
            is EcoTripReview.Ready ->
                (listOf(verdictLine(review.verdict)) + review.findings.map(::findingLine))
                    .joinToString("\n")
            is EcoTripReview.Unavailable -> reviewUnavailable(review.reason)
            null -> reviewUnavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        binding.detailEcoReview.text = text
        binding.detailEcoReview.contentDescription = text
    }

    private fun reviewUnavailable(reason: UnavailableReason): String = getString(
        R.string.trip_eco_review_unavailable,
        getString(reasons.reasonRes(reason)),
    )

    private fun verdictLine(verdict: EcoVerdict): String {
        val band = verdict.band.value
        val delta = verdict.deltaPercent
        if (band == null || delta == null) {
            return getString(
                R.string.trip_eco_verdict_unavailable,
                getString(
                    reasons.reasonRes(verdict.band.reason ?: UnavailableReason.INSUFFICIENT_SAMPLES)
                ),
            )
        }
        return getString(R.string.trip_eco_verdict, getString(ecoBandRes(band)), delta.roundToInt())
    }

    private fun findingLine(finding: EcoFinding): String = when (finding) {
        is EcoFinding.MotorwaySpeed -> getString(
            if (finding.basis == SpeedWhatIfBasis.ENERGY_KWH) {
                R.string.trip_eco_finding_motorway_energy
            } else {
                R.string.trip_eco_finding_motorway_soc
            },
            finding.referenceSpeedKmh,
            finding.motorwayDistanceKm,
            finding.savingLow,
            finding.savingHigh,
        )
        is EcoFinding.Cabin ->
            getString(R.string.trip_eco_finding_cabin, finding.kwh, finding.sharePercent)
        is EcoFinding.Steadiness -> getString(
            R.string.trip_eco_finding_steadiness,
            finding.harshSharePercent.roundToInt(),
        )
    }

    private fun renderAttribution(result: EnergyAttributionResult?) {
        binding.detailAttribution.text = when (result) {
            is EnergyAttributionResult.Ready -> attributionText(result.attribution)
            is EnergyAttributionResult.Unavailable -> getString(
                R.string.trip_attribution_unavailable,
                getString(
                    when {
                        batteryPowerNeverPublished() -> R.string.reason_power_never_published
                        result.reason == UnavailableReason.MODEL_NOT_TRAINED ->
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

    private fun distance(value: Double): String = format(PATTERN_DISTANCE, value)
    private fun energy(value: Double): String = format(PATTERN_ENERGY, value)
    private fun consumption(value: Double): String = format(PATTERN_CONSUMPTION, value)
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

    private data class OverviewCard(val totals: TripTotals, val trace: List<Float?>)

    private data class Overview(
        val windowKm: Double,
        val coveredKm: Double,
        val netKwh: Double,
        val consumption: Double?,
        val rangeKm: Double?,
        val reference: Double,
        val trace: List<Float?>,
        val tripAFromMs: Long,
        val current: OverviewCard?,
        val sinceCharge: OverviewCard?,
        val tripA: OverviewCard,
        val history: OverviewCard,
    )

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
        const val STATE_OVERVIEW = "show_overview"
        const val OVERVIEW_FILE = "chargepilot_trip_overview"
        const val KEY_WINDOW = "window_km"
        const val KEY_TRIP_A = "trip_a_from_ms"
        const val DEFAULT_WINDOW_KM = 150.0
        /** A live trip moves the overview; every ten seconds is often enough to see it move. */
        const val OVERVIEW_REFRESH_MS = 10_000L
        val WINDOW_BUTTONS = mapOf(
            TripOverview.WINDOWS_KM[0] to R.id.window15,
            TripOverview.WINDOWS_KM[1] to R.id.window150,
            TripOverview.WINDOWS_KM[2] to R.id.window300,
        )
        const val DASH = "—"
    }

    private val gateExpiry = Runnable { renderSpeedWhatIfGate() }
}
