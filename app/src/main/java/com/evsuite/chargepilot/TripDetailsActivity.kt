package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.evsuite.chargepilot.databinding.ActivityTripDetailsBinding
import com.evsuite.chargepilot.databinding.RowDetailBarBinding
import com.evsuite.hardware.telemetry.BatteryCapacityConfig
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.UnavailableReason
import com.evsuite.hardware.telemetry.model.AttributedEnergyEstimate
import com.evsuite.hardware.telemetry.model.EnergyAttribution
import com.evsuite.hardware.telemetry.model.EnergyAttributionCalculator
import com.evsuite.hardware.telemetry.model.EnergyAttributionResult
import com.evsuite.hardware.telemetry.model.ResidualAttribution
import com.evsuite.hardware.telemetry.model.ResidualContext
import com.evsuite.hardware.telemetry.model.ResidualFinding
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * One completed trip, explained: where its energy went on the left, what its speeds cost on the
 * right.
 *
 * It replaces the energy-breakdown and speed-comparison screens. Their figures, their refusals
 * and their provenance are unchanged; only the telling moved from paragraphs to bars. The claim
 * each figure makes — derived, measured, estimated — is written once above its group rather
 * than under every row, and every row still says it to TalkBack. The modelled motorway
 * comparison keeps its parked-only gate; the recorded figures, like the ledger behind them,
 * do not need one.
 */
class TripDetailsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTripDetailsBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-trip-details")
    }
    private var recorder: TripRecordingService? = null
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null
    private var bound = false
    private var whatIf: SpeedWhatIfResult? = null
    private var loaded = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@TripDetailsActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                renderWhatIf()
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            renderWhatIf()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            speedKmh = null
            speedObservedAtMs = null
            renderWhatIf()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        val startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
        if (startedAtMs == INVALID_ID) {
            finish()
            return
        }
        binding.detailsTitle.text = getString(
            R.string.trip_details_title_date,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(startedAtMs)),
        )
        load(startedAtMs)
        renderWhatIf()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) renderWhatIf()
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

    private fun load(startedAtMs: Long) {
        disk.execute {
            val trips = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
            val trip = trips.firstOrNull { it.summary.startedAtMs == startedAtMs }
            val model = LocalEnergyModel.loadOrTrain(
                filesDir,
                trips,
                trip?.summary?.batteryPowerEvidence,
            )
            // The charge fit, for the car that publishes no power — which is this one.
            val socModel = (SocConsumptionFitter().fit(trips) as? SocConsumptionFitResult.Ready)
                ?.model
            val energy = trip?.let { EnergyAttributionCalculator.calculate(it, model) }
            // Only where the kilowatt-hour split cannot be made. The two are never blended: a
            // kWh integrated from power and a percent read off the gauge are different
            // measurements, and a screen mixing them would be read as one quantity twice.
            val charge = if (energy is EnergyAttributionResult.Ready) {
                null
            } else {
                trip?.let { ChargeAttributionCalculator.calculate(it, socModel) }
            }
            val comparison = trip?.let { SpeedWhatIfCalculator.calculate(it, model, socModel) }
                ?: SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
            // Only trips whose power and speed were converted by the same rules as this one.
            val measured = trip?.summary?.let { own ->
                TripOverview.speedBands(
                    trips.filter {
                        it.summary.batteryPowerEvidence == own.batteryPowerEvidence &&
                            it.summary.speedEvidence == own.speedEvidence
                    },
                )
            }.orEmpty()
            val measuredTrip = TripOverview.speedBands(listOfNotNull(trip))
            val pack = VehicleSettings.read(this).pack
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderEnergy(
                    energy,
                    charge,
                    trip?.summary,
                    pack,
                    measuredTrip.firstOrNull { it.fromKmh == 0 },
                )
                renderSpeeds(measured, measuredTrip)
                whatIf = comparison
                loaded = true
                renderWhatIf()
            }
        }
    }

    // ---------- Energy ----------

    private fun renderEnergy(
        result: EnergyAttributionResult?,
        charge: ChargeAttributionResult?,
        summary: EnergyTripSummary?,
        pack: BatteryCapacityConfig?,
        standing: SpeedBand?,
    ) {
        binding.energyRows.removeAllViews()
        when {
            result is EnergyAttributionResult.Ready -> renderAttribution(result.attribution)
            charge is ChargeAttributionResult.Ready -> renderChargeAttribution(charge.attribution)
            else -> renderTotals(result, charge, summary, pack, standing)
        }
    }

    private fun renderAttribution(value: EnergyAttribution) {
        binding.energyVerdict.text = summary(value.residuals)
        val consumed = checkNotNull(value.totalConsumedKwh.value)
        val scale = max(consumed, value.modelledTraction.bandHighKwh)
        val rows = binding.energyRows
        group(rows, R.string.energy_breakdown_claim_derived_power)
        bar(rows, R.string.energy_breakdown_consumed, R.string.energy_breakdown_claim_derived_power,
            energy(consumed), filled(consumed / scale, R.color.ev_accent))
        val regenerated = checkNotNull(value.measuredRegenerationKwh.value)
        bar(rows, R.string.energy_breakdown_regenerated, R.string.energy_breakdown_claim_derived_power,
            energy(regenerated), filled(regenerated / scale, R.color.ev_ok))
        val net = checkNotNull(value.netBatteryEnergyKwh.value)
        bar(rows, R.string.energy_breakdown_net, R.string.energy_breakdown_claim_derived_net,
            energy(net), filled(net / scale, R.color.ev_accent))
        val remainder = checkNotNull(value.unmodelledDiscrepancyKwh.value)
        bar(rows, R.string.energy_breakdown_discrepancy, R.string.energy_breakdown_claim_discrepancy,
            energy(remainder), filled(remainder / scale, R.color.ev_text_secondary))

        group(rows, R.string.energy_breakdown_claim_estimated)
        bar(rows, R.string.energy_breakdown_traction, R.string.energy_breakdown_claim_estimated,
            band(value.modelledTraction), banded(value.modelledTraction.bandLowKwh,
                value.modelledTraction.bandHighKwh, scale))
        value.residuals.forEach { residual ->
            bar(rows, residualLabel(residual.context), R.string.energy_breakdown_claim_residual,
                residualValue(residual), banded(residual.estimate.bandLowKwh,
                    residual.estimate.bandHighKwh, scale, residual.finding))
        }
        note(getString(R.string.energy_breakdown_reconciliation, value.reconciliationErrorKwh))
    }

    /**
     * The same ledger, measured on the gauge instead of on the power line. The rows keep their
     * order so a driver who has seen the kWh version reads this one the same way.
     */
    private fun renderChargeAttribution(value: ChargeAttribution) {
        binding.energyVerdict.text = getString(
            R.string.energy_breakdown_charge_summary,
            chargeVerdict(value.residuals),
        )
        val used = value.measuredDropPercent
        val scale = max(used, value.modelledDriving.bandHighPercent)
        val rows = binding.energyRows
        group(rows, R.string.energy_breakdown_claim_derived_charge)
        bar(rows, R.string.energy_breakdown_charge_used, R.string.energy_breakdown_claim_derived_charge,
            percent(used), filled(used / scale, R.color.ev_accent))
        bar(rows, R.string.energy_breakdown_discrepancy, R.string.energy_breakdown_claim_charge_uncovered,
            percent(value.uncoveredDropPercent),
            filled(value.uncoveredDropPercent / scale, R.color.ev_text_secondary))
        bar(rows, R.string.energy_breakdown_charge_distance, R.string.energy_breakdown_claim_derived_distance,
            String.format(Locale.getDefault(), PATTERN_DISTANCE, value.distanceKm), null)

        group(rows, R.string.energy_breakdown_claim_charge_estimated)
        bar(rows, R.string.energy_breakdown_driving, R.string.energy_breakdown_claim_charge_estimated,
            chargeBand(value.modelledDriving), banded(value.modelledDriving.bandLowPercent,
                value.modelledDriving.bandHighPercent, scale))
        value.residuals.forEach { residual ->
            bar(rows, residualLabel(residual.context), R.string.energy_breakdown_claim_charge_residual,
                chargeResidualValue(residual), banded(residual.estimate.bandLowPercent,
                    residual.estimate.bandHighPercent, scale, residual.finding))
        }
        note(getString(R.string.energy_breakdown_charge_rate, 1.0 / value.percentPerKm))
    }

    /**
     * Neither model can split this trip. It still spent energy over a distance: what the power
     * line recorded comes first, needing no model; then the gauge drop priced with the pack the
     * driver declared, named as the estimate it is. The screen ends on figures, not on a refusal.
     */
    private fun renderTotals(
        result: EnergyAttributionResult?,
        charge: ChargeAttributionResult?,
        summary: EnergyTripSummary?,
        pack: BatteryCapacityConfig?,
        standing: SpeedBand?,
    ) {
        binding.energyVerdict.setText(
            when {
                // On a car that publishes no power, the charge path is the only one that will
                // ever answer, so its reason is the one the driver can act on.
                charge is ChargeAttributionResult.Unavailable && batteryPowerNeverPublished() ->
                    chargeReason(charge.reason)
                result is EnergyAttributionResult.Unavailable &&
                    result.reason == UnavailableReason.INSUFFICIENT_SAMPLES ->
                    R.string.energy_breakdown_missing_power
                else -> R.string.energy_breakdown_model_unavailable
            },
        )
        val rows = binding.energyRows
        val distanceKm = summary?.recordedDistanceKm?.takeIf { it >= MIN_DISTANCE_KM }
        val consumed = summary?.consumedKwh
        val regenerated = summary?.regeneratedKwh
        if (distanceKm != null && consumed != null && regenerated != null && consumed > 0.0) {
            val net = consumed - regenerated
            group(rows, R.string.energy_breakdown_claim_derived_power)
            bar(rows, R.string.energy_breakdown_consumed, R.string.energy_breakdown_claim_derived_power,
                energy(consumed), filled(consumed / consumed, R.color.ev_accent))
            bar(rows, R.string.energy_breakdown_regenerated, R.string.energy_breakdown_claim_derived_power,
                energy(regenerated), filled(regenerated / consumed, R.color.ev_ok))
            bar(rows, R.string.energy_breakdown_net, R.string.energy_breakdown_claim_derived_net,
                getString(R.string.trip_details_net_value, net, net * 100.0 / distanceKm),
                filled(net / consumed, R.color.ev_accent))
        }
        val still = standing?.takeIf { it.durationMs >= 60_000L }
        if (still != null && consumed != null) {
            note(getString(
                R.string.energy_breakdown_measured_standing,
                still.netKwh,
                (still.durationMs / 60_000L).toInt(),
            ))
        }
        renderGauge(rows, summary, pack, distanceKm)
    }

    /** The gauge drop, drawn where it happened on the 0–100 % track, priced with the pack. */
    private fun renderGauge(
        rows: LinearLayout,
        summary: EnergyTripSummary?,
        pack: BatteryCapacityConfig?,
        distanceKm: Double?,
    ) {
        val start = summary?.startSocPercent?.toDouble() ?: return
        val end = summary.endSocPercent?.toDouble() ?: return
        val drop = start - end
        val kwh = pack?.energyAtSocKwh(drop)?.value?.takeIf { it > 0.0 }
        group(rows, R.string.trip_details_gauge_claim)
        val value = if (kwh != null && distanceKm != null) {
            getString(R.string.trip_details_gauge_value, start, end, kwh, kwh * 100.0 / distanceKm)
        } else {
            getString(R.string.trip_details_gauge_value_bare, start, end)
        }
        bar(rows, R.string.label_soc_change, R.string.trip_details_gauge_claim, value,
            listOf(BarGaugeView.Span((end / PERCENT).toFloat(), (start / PERCENT).toFloat(),
                R.color.ev_accent)))
    }

    // ---------- Speed ----------

    /** One bar per speed band: its length your recorded trips, the mark this trip. */
    private fun renderSpeeds(measured: List<SpeedBand>, measuredTrip: List<SpeedBand>) {
        val rows = binding.speedRows
        rows.removeAllViews()
        val trip = measuredTrip.associateBy { it.fromKmh }
            .filterValues { it.distanceKm >= MIN_BAND_KM }
        val bands = measured.filter { it.fromKmh != 0 && it.distanceKm >= MIN_BAND_KM }
        if (bands.isEmpty()) {
            binding.speedLegend.setText(R.string.trip_details_speed_none)
        }
        val scale = (bands.map(::per100) + trip.values.filter { it.fromKmh != 0 }.map(::per100))
            .maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        bands.forEach { band ->
            val label = band.toKmh?.let { "${band.fromKmh}–$it" } ?: "${band.fromKmh}+"
            val own = trip[band.fromKmh]
            val value = if (own == null) {
                getString(R.string.trip_details_band_value, per100(band), band.distanceKm)
            } else {
                getString(R.string.trip_details_band_value_trip, per100(band), per100(own))
            }
            addBar(
                rows,
                getString(R.string.trip_details_band_label, label),
                getString(R.string.trip_details_speed_legend),
                value,
                filled(per100(band) / scale, R.color.ev_accent, BarGaugeView.LIGHT),
                own?.let { (per100(it) / scale).toFloat() },
            )
        }
        val still = measuredTrip.firstOrNull { it.fromKmh == 0 }
            ?.takeIf { it.durationMs >= MIN_STANDING_MS }
        binding.speedNote.visibility = if (still == null) View.GONE else View.VISIBLE
        still?.let {
            binding.speedNote.text = getString(
                R.string.speed_what_if_measured_standing,
                (it.durationMs / 60_000L).toInt(),
                it.netKwh / (it.durationMs / MILLIS_PER_HOUR),
            )
        }
    }

    private fun renderWhatIf() {
        binding.root.removeCallbacks(gateExpiry)
        binding.whatIfRows.removeAllViews()
        val nowMs = System.currentTimeMillis()
        when (ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)) {
            ParkedDeletionGate.MOVING -> binding.whatIfStatus.setText(R.string.speed_what_if_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE ->
                binding.whatIfStatus.setText(R.string.speed_what_if_speed_unavailable)
            ParkedDeletionGate.PARKED -> {
                val ageMs = nowMs - checkNotNull(speedObservedAtMs)
                binding.root.postDelayed(
                    gateExpiry,
                    ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
                )
                if (!loaded) {
                    binding.whatIfStatus.setText(R.string.speed_what_if_loading)
                } else {
                    renderComparison(whatIf)
                }
            }
        }
    }

    /**
     * Each reference speed as a band either side of the centre line: right of it the model
     * expects less spent than you did, left of it more. Colour repeats the side; the sign in the
     * figure says it again, so neither carries it alone.
     */
    private fun renderComparison(result: SpeedWhatIfResult?) {
        val ready = result as? SpeedWhatIfResult.Ready
        if (ready == null) {
            binding.whatIfStatus.setText(
                (result as? SpeedWhatIfResult.Unavailable)?.let { reason(it.reason) }
                    ?: R.string.reason_model_not_trained,
            )
            return
        }
        val charge = ready.basis == SpeedWhatIfBasis.STATE_OF_CHARGE_PERCENT
        binding.whatIfStatus.text = getString(
            if (charge) R.string.speed_what_if_ready_charge else R.string.speed_what_if_ready,
            ready.motorwayDistanceKm,
        )
        // The unit is written on every figure, because "4" in percent and "4" in kilowatt-hours
        // are not the same drive told twice.
        val unit = if (charge) "%" else "kWh"
        val reach = ready.comparisons.maxOfOrNull { max(abs(it.deltaLow), abs(it.deltaHigh)) }
            ?.takeIf { it > 0.0 } ?: 1.0
        ready.comparisons.forEach { row ->
            val saves = row.deltaLow + row.deltaHigh >= 0.0
            addBar(
                binding.whatIfRows,
                getString(R.string.trip_details_what_if_speed, row.referenceSpeedKmh),
                getString(R.string.energy_breakdown_claim_estimated),
                getString(
                    R.string.trip_details_what_if_value,
                    range(row.deltaLow, row.deltaHigh, unit),
                    range(row.rangeDeltaLowKm, row.rangeDeltaHighKm, "km"),
                ),
                listOf(
                    BarGaugeView.Span(
                        (CENTRE + row.deltaLow / reach * CENTRE).toFloat(),
                        (CENTRE + row.deltaHigh / reach * CENTRE).toFloat(),
                        if (saves) R.color.ev_ok else R.color.ev_warn,
                    ),
                ),
                CENTRE.toFloat(),
            )
        }
    }

    private fun reason(reason: SpeedWhatIfUnavailable): Int = when (reason) {
        // This comparison is fitted from battery power, and on a car that never publishes it
        // the honest reason is the car, not a fit that has not happened yet.
        SpeedWhatIfUnavailable.MODEL_NOT_TRAINED ->
            if (batteryPowerNeverPublished()) {
                R.string.power_never_published
            } else {
                R.string.reason_model_not_trained
            }
        SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION -> R.string.speed_what_if_no_motorway
        SpeedWhatIfUnavailable.MOTORWAY_ENERGY_UNAVAILABLE ->
            R.string.speed_what_if_energy_unavailable
        SpeedWhatIfUnavailable.MOTORWAY_TEMPERATURE_UNAVAILABLE ->
            R.string.speed_what_if_temperature_unavailable
        SpeedWhatIfUnavailable.RANGE_BASELINE_UNAVAILABLE ->
            R.string.speed_what_if_range_unavailable
        SpeedWhatIfUnavailable.NO_REFERENCE_SPEED_IN_ENVELOPE ->
            R.string.speed_what_if_envelope_unavailable
        SpeedWhatIfUnavailable.MOTORWAY_CHARGE_UNAVAILABLE ->
            R.string.speed_what_if_charge_unavailable
        SpeedWhatIfUnavailable.MOTORWAY_CHARGE_DROP_TOO_SMALL ->
            R.string.speed_what_if_charge_too_small
    }

    // ---------- Rows ----------

    /** The claim a group of rows makes, said once above them. */
    private fun group(container: LinearLayout, claim: Int) {
        val header = TextView(this)
        TextViewCompat.setTextAppearance(header, R.style.Text_EV_Secondary)
        header.setText(claim)
        header.setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_md), 0, 0)
        container.addView(header)
    }

    private fun bar(
        container: LinearLayout,
        label: Int,
        claim: Int,
        value: String,
        spans: List<BarGaugeView.Span>?,
    ) = addBar(container, getString(label), getString(claim), value, spans, null)

    private fun addBar(
        container: LinearLayout,
        label: String,
        claim: String,
        value: String,
        spans: List<BarGaugeView.Span>?,
        mark: Float?,
    ) {
        val row = RowDetailBarBinding.inflate(layoutInflater, container, false)
        row.rowLabel.text = label
        row.rowValue.text = value
        if (spans == null) {
            row.rowBar.visibility = View.GONE
        } else {
            row.rowBar.show(spans, mark)
        }
        row.root.contentDescription =
            getString(R.string.energy_breakdown_row_description, label, claim, value)
        container.addView(row.root)
    }

    private fun filled(
        fraction: Double,
        @ColorRes color: Int,
        alpha: Int = BarGaugeView.OPAQUE,
    ) = listOf(BarGaugeView.Span(0f, fraction.toFloat(), color, alpha))

    /**
     * An estimate's band, light, from its low to its high end. A band reaching below zero is
     * drawn from zero: the figure beside it still says how far below.
     */
    private fun banded(
        low: Double,
        high: Double,
        scale: Double,
        finding: ResidualFinding = ResidualFinding.DISTINGUISHABLE,
    ) = listOf(
        BarGaugeView.Span(
            (low.coerceAtLeast(0.0) / scale).toFloat(),
            (high.coerceAtLeast(0.0) / scale).toFloat(),
            if (finding == ResidualFinding.NEGATIVE_MODEL_ERROR) R.color.ev_warn else R.color.ev_accent,
            BarGaugeView.LIGHT,
        ),
    )

    private fun note(text: String) {
        binding.energyNote.visibility = View.VISIBLE
        binding.energyNote.text = listOfNotNull(
            binding.energyNote.text?.takeIf { it.isNotEmpty() },
            text,
        ).joinToString("\n")
    }

    // ---------- Wording, unchanged from the two screens this one replaces ----------

    private fun chargeReason(reason: ChargeAttributionUnavailable): Int = when (reason) {
        ChargeAttributionUnavailable.MODEL_NOT_TRAINED ->
            R.string.energy_breakdown_charge_untrained
        ChargeAttributionUnavailable.NO_MODELLED_INTERVAL ->
            R.string.energy_breakdown_charge_no_interval
        ChargeAttributionUnavailable.CHARGE_DROP_TOO_SMALL ->
            R.string.energy_breakdown_charge_too_small
    }

    private fun residualLabel(context: ResidualContext): Int = when (context) {
        ResidualContext.CLIMATE_ACTIVE -> R.string.energy_breakdown_residual_active
        ResidualContext.CLIMATE_INACTIVE -> R.string.energy_breakdown_residual_inactive
        ResidualContext.CLIMATE_UNKNOWN -> R.string.energy_breakdown_residual_unknown
        ResidualContext.WINDOWS_OPEN -> R.string.energy_breakdown_residual_windows
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

    private fun chargeResidualValue(value: ChargeResidual): String = when (value.finding) {
        ResidualFinding.DISTINGUISHABLE -> chargeBand(value.estimate)
        ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO -> getString(
            R.string.energy_breakdown_indistinguishable,
            chargeBand(value.estimate),
        )
        ResidualFinding.NEGATIVE_MODEL_ERROR -> getString(
            R.string.energy_breakdown_negative_error,
            chargeBand(value.estimate),
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

    /**
     * The same four verdicts as the kWh path, worded in charge: nothing here was measured in
     * kilowatt-hours, and a sentence about measured energy on a screen of percentages reads as
     * a different quantity than the one the rows show.
     */
    private fun chargeVerdict(residuals: List<ChargeResidual>): String = when {
        residuals.any { it.finding == ResidualFinding.NEGATIVE_MODEL_ERROR } ->
            getString(R.string.energy_breakdown_charge_verdict_negative)
        residuals.none { it.finding == ResidualFinding.DISTINGUISHABLE } ->
            getString(R.string.energy_breakdown_charge_verdict_noise)
        residuals.any {
            it.context == ResidualContext.CLIMATE_ACTIVE &&
                it.finding == ResidualFinding.DISTINGUISHABLE
        } -> getString(R.string.energy_breakdown_charge_verdict_climate)
        else -> getString(R.string.energy_breakdown_charge_verdict_other)
    }

    private fun per100(band: SpeedBand) = band.netKwh * 100.0 / band.distanceKm

    private fun energy(value: Double): String =
        String.format(Locale.getDefault(), PATTERN_ENERGY, value)

    private fun percent(value: Double): String =
        String.format(Locale.getDefault(), PATTERN_SOC, value)

    private fun chargeBand(value: ChargeEstimate): String = String.format(
        Locale.getDefault(),
        "≈ %.1f–%.1f %%",
        value.bandLowPercent,
        value.bandHighPercent,
    )

    private fun band(value: AttributedEnergyEstimate): String = String.format(
        Locale.getDefault(),
        "≈ %.2f–%.2f kWh",
        value.bandLowKwh,
        value.bandHighKwh,
    )

    private fun range(low: Double, high: Double, unit: String): String =
        String.format(Locale.getDefault(), "≈ %+.2f–%+.2f %s", low, high, unit)

    private val gateExpiry = Runnable { renderWhatIf() }

    companion object {
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE
        private const val HISTORY_FILE = "trips.json"
        private const val MIN_DISTANCE_KM = 0.1
        private const val MIN_STANDING_MS = 60_000L
        private const val PERCENT = 100.0
        private const val CENTRE = 0.5

        /** Under half a kilometre, a kWh/100 km figure is one traffic light, not a speed. */
        private const val MIN_BAND_KM = 0.5
        private const val MILLIS_PER_HOUR = 3_600_000.0

        fun forTrip(context: Context, startedAtMs: Long) =
            Intent(context, TripDetailsActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)
    }
}
