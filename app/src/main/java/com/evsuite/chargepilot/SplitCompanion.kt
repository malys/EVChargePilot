package com.evsuite.chargepilot

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * CP-094: the companion quarter beside the car's map. Glance-only while moving: charge and range,
 * the destination against the range, the drive's eco score, the recent consumption and the other
 * roads the plan offered.
 *
 * Drawn by [MapCompanionOverlay] over MG4 Navigator on the car, and by [NavSplitActivity] in the
 * emulator preview, from the extras [intent] builds.
 *
 * The guidance is read through the synchronous getters, like the arrival screen reads it. This
 * never registers with the adapter's listener.
 */
internal class SplitCompanion(
    private val context: Context,
    private val binding: ActivityNavSplitBinding,
    private val extras: Intent,
    private val preview: Boolean,
    private val recorder: () -> TripRecordingService?,
) {

    /** One road the plan offered, as the split screen draws it. */
    data class SplitRoad(
        val name: String,
        val km: Double,
        val minutes: Double,
        /** Charge against the routed road, signed as cost; null on the routed road itself. */
        val chargeLow: Double?,
        val chargeHigh: Double?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-split")
    }
    private var closed = false
    private var guidanceReadAtMs = 0L
    private var remainingKm: Double? = null
    private var toStopKm: Double? = null
    private var drivenBaselineKm: Double? = null
    private var traceReadAtMs = 0L
    private val consumption by lazy { RecentConsumption(context) }

    init {
        renderRoads(
            roads(extras).ifEmpty { if (preview) PREVIEW_ROADS else emptyList() },
            extras.getIntExtra(EXTRA_CHOSEN, 0),
        )
    }

    /** Stops the reads; what is still queued is dropped. */
    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun onUi(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    fun render(value: EnergySnapshot) {
        val unavailable = context.getString(R.string.value_unavailable)
        val soc = value.socPercent?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: unavailable
        binding.splitSocRing.show(value.socPercent?.div(PERCENT), soc)
        binding.splitSocRing.contentDescription = context.getString(R.string.nav_split_battery, soc)
        val range = value.rangeKm?.let { String.format(Locale.getDefault(), PATTERN_DISTANCE_WHOLE, it) } ?: unavailable
        binding.splitRange.text = smallUnits(range)
        // The label is gone from the screen, not from a screen reader.
        binding.splitRange.contentDescription = context.getString(R.string.nav_split_range_label) + " " + range
        renderReach(value)
        renderEco(value)
        renderConsumption(value)
        // Several binder reads per refresh: every few seconds is enough for a remaining distance.
        if (value.timestampMs - guidanceReadAtMs < GUIDANCE_REFRESH_MS) return
        guidanceReadAtMs = value.timestampMs
        worker.execute {
            val guidance = if (preview) null else runCatching { SaicNavGuidance.readNow() }.getOrNull()
            val km = if (preview) PREVIEW_KM else guidance?.remainingDistanceKm(FirmwareInfo.getGeneration())
            val minutes = if (preview) PREVIEW_MINUTES else guidance?.remainingMinutes
            val road = if (preview) PREVIEW_ROAD else guidance?.road
            onUi {
                val toStop = extras.getBooleanExtra(EXTRA_TO_STOP, false)
                val routeKm = extras.getDoubleExtra(EXTRA_ROUTE_KM, Double.NaN).takeUnless(Double::isNaN)
                remainingKm = destinationKm(toStop, km, routeKm, drivenKm(value))
                toStopKm = km.takeIf { toStop }
                val left = remainingKm
                binding.splitDestination.visibility = if (left == null) View.VISIBLE else View.GONE
                binding.splitRoute.visibility = if (left == null) View.GONE else View.VISIBLE
                binding.splitRoute.text = smallUnits(when {
                    left == null -> context.getString(R.string.nav_split_no_guidance)
                    toStop -> context.getString(
                        R.string.nav_split_route_stop,
                        String.format(Locale.getDefault(), "%.0f", left),
                        toStopKm?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: unavailable,
                    )
                    minutes != null -> context.getString(
                        R.string.nav_split_route_eta,
                        String.format(Locale.getDefault(), "%.1f", left),
                        minutes.toString(),
                        arrivalClock(minutes),
                    )
                    else -> context.getString(
                        R.string.nav_split_route,
                        String.format(Locale.getDefault(), "%.1f", left),
                        unavailable,
                    )
                })
                binding.splitRoad.text = road.orEmpty()
                renderReach(recorder()?.latest)
            }
        }
    }

    /** The clock time the car's minutes left come to, in the head unit's own 12/24 h format. */
    private fun arrivalClock(minutes: Int): String =
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + minutes * 60_000L))

    /**
     * Distance driven since the companion opened, which is the tap.
     *
     * shortcut: a trip that ends and restarts mid-route resets the count, so the destination reads
     * further than it is; the adapter's odometer would survive that, at a whole kilometre per step.
     */
    private fun drivenKm(value: EnergySnapshot): Double {
        val trip = EnergyTripSession.current(value.timestampMs)?.recordedDistanceKm ?: 0.0
        val baseline = drivenBaselineKm ?: trip.also { drivenBaselineKm = it }
        return (trip - baseline).coerceAtLeast(0.0)
    }

    /** The trip's share of the range on the bar, and what is left of the range and charge on arriving. */
    private fun renderReach(value: EnergySnapshot?) {
        val rangeKm = value?.rangeKm?.toDouble()
        val km = remainingKm
        if (km == null || rangeKm == null || rangeKm <= 0.0) {
            binding.splitReach.show(emptyList())
            binding.splitMargin.text = ""
            binding.splitMargin.visibility = View.GONE
            return
        }
        val short = km > rangeKm
        binding.splitReach.show(
            listOf(
                BarGaugeView.Span(0f, (km / rangeKm).toFloat(), if (short) R.color.ev_error else R.color.ev_accent),
            ),
        )
        binding.splitMargin.visibility = View.VISIBLE
        binding.splitMargin.text = smallUnits(if (short) context.getString(R.string.nav_split_margin_short) else context.getString(
            R.string.nav_split_margin,
            String.format(Locale.getDefault(), "%.0f", rangeKm - km),
            value.socPercent
                ?.let { String.format(Locale.getDefault(), "%.0f", arrivalSoc(it.toDouble(), rangeKm, km)) }
                ?: context.getString(R.string.value_unavailable),
        ))
    }

    /**
     * The last kilometres as the trip screen draws them: the line is the glance, the average the
     * figure. [RecentConsumption] is shared with the energy dashboard.
     */
    private fun renderConsumption(value: EnergySnapshot) {
        if (value.timestampMs - traceReadAtMs < TRACE_REFRESH_MS) return
        traceReadAtMs = value.timestampMs
        worker.execute {
            val recent = consumption.read(value.timestampMs)
            onUi {
                val average = recent.consumptionKwhPer100Km
                val reference = recent.referenceKwhPer100Km
                binding.splitArc.show(
                    average,
                    reference,
                    arcScale(average, reference),
                    if (average != null && average > reference) R.color.ev_warn else R.color.ev_accent,
                )
                binding.splitConsumption.text = average
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) }
                    ?: context.getString(R.string.value_unavailable)
                binding.splitArc.contentDescription = average
                    ?.let { context.getString(R.string.nav_split_consumption_value, it) }
                binding.splitConsumptionSpan.text = if (recent.consumptionKwhPer100Km == null) {
                    ""
                } else {
                    context.getString(R.string.nav_split_consumption_span, recent.coveredKm)
                }
            }
        }
    }

    /** Twice the reference, or more when the average runs past it: the tick sits mid-arc. */
    private fun arcScale(average: Double?, reference: Double): Double {
        val top = maxOf(reference * 2.0, (average ?: 0.0) * 1.2)
        return kotlin.math.ceil(top / ARC_STEP) * ARC_STEP
    }

    /** CP-093's score, live: the drive so far against the verdict the recorder last published. */
    private fun renderEco(value: EnergySnapshot) {
        val verdict = DashboardFrame.eco
        val score = EnergyTripSession.current(value.timestampMs)?.let { EcoScore.of(it, verdict) }
        binding.splitEcoBand.text = recorder()?.eco?.band(verdict) ?: context.getString(R.string.value_unavailable)
        if (score == null) {
            binding.splitEcoRing.show(null, context.getString(R.string.value_unavailable))
            binding.splitEcoParts.text = context.getString(R.string.nav_split_eco_waiting)
            return
        }
        binding.splitEcoRing.show(
            score.total / PERCENT,
            score.total.toString(),
            if (score.total < ECO_WARN_BELOW) R.color.ev_warn else R.color.ev_accent,
        )
        binding.splitEcoParts.text = context.getString(
            R.string.nav_split_eco_parts,
            score.anticipation?.toString() ?: DASH,
            score.smoothness?.toString() ?: DASH,
            score.efficiency?.toString() ?: DASH,
        )
    }

    /**
     * One line and one bar per road other than [chosen], which the destination block already
     * shows; the bar's length is the road's time against the longest.
     */
    private fun renderRoads(roads: List<SplitRoad>, chosen: Int) {
        if (roads.size < 2) return
        val fastest = roads.minOf { it.minutes }
        val longest = roads.maxOf { it.minutes }.takeIf { it > 0.0 } ?: return
        val density = context.resources.displayMetrics.density
        roads.forEachIndexed { at, road ->
            if (at == chosen) return@forEachIndexed
            val delta = road.minutes - fastest
            val line = context.getString(
                R.string.route_choices_road,
                road.name,
                String.format(Locale.getDefault(), "%.0f", road.km),
                String.format(Locale.getDefault(), "%.0f", road.minutes),
                if (delta < 0.5) context.getString(R.string.route_choices_delta_fastest)
                else context.getString(R.string.route_choices_delta_slower, String.format(Locale.getDefault(), "%.0f", delta)),
            )
            binding.splitRoads.addView(
                TextView(context).apply {
                    setTextAppearance(R.style.Text_EV_Secondary)
                    text = if (road.chargeLow == null || road.chargeHigh == null) line else line + "\n" + context.getString(
                        R.string.nav_split_road_charge,
                        String.format(Locale.getDefault(), "%+.1f", road.chargeLow),
                        String.format(Locale.getDefault(), "%+.1f", road.chargeHigh),
                    )
                    setPadding(0, (SPACING_DP * density).toInt(), 0, 0)
                },
            )
            binding.splitRoads.addView(
                BarGaugeView(context).apply {
                    show(
                        listOf(
                            BarGaugeView.Span(
                                0f,
                                (road.minutes / longest).toFloat(),
                                R.color.ev_accent,
                                BarGaugeView.LIGHT,
                            ),
                        ),
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ROAD_BAR_DP * density).toInt()),
            )
        }
        binding.splitRoads.visibility = View.VISIBLE
    }

    companion object {
        private const val GUIDANCE_REFRESH_MS = 5_000L
        private const val PERCENT = 100f
        private const val ARC_STEP = 5.0
        private const val ECO_WARN_BELOW = 50
        private const val SPACING_DP = 8f
        private const val ROAD_BAR_DP = 8f
        private const val DASH = "—"
        private const val TRACE_REFRESH_MS = 10_000L

        private const val PREVIEW_KM = 42.3
        private const val PREVIEW_MINUTES = 37
        private const val PREVIEW_ROAD = "A64"
        private val PREVIEW_ROADS = listOf(
            SplitRoad("A64", 42.3, 37.0, null, null),
            SplitRoad("D817", 39.8, 52.0, -2.0, -0.5),
        )

        private const val EXTRA_NAMES = "split.road.names"
        private const val EXTRA_KM = "split.road.km"
        private const val EXTRA_MINUTES = "split.road.minutes"
        private const val EXTRA_CHARGE_LOW = "split.road.charge_low"
        private const val EXTRA_CHARGE_HIGH = "split.road.charge_high"
        private const val EXTRA_CHOSEN = "split.road.chosen"
        private const val EXTRA_TO_STOP = "split.to_stop"
        private const val EXTRA_ROUTE_KM = "split.route_km"

        /**
         * Kilometres left to the destination.
         *
         * Without a stop the car's figure is it. With one, the car counts to the stop (its next
         * waypoint), so the destination is the planned road's length less what has been driven.
         */
        internal fun destinationKm(toStop: Boolean, carKm: Double?, routeKm: Double?, drivenKm: Double): Double? =
            if (!toStop) carKm else routeKm?.let { (it - drivenKm).coerceAtLeast(0.0) }

        /**
         * The charge left on arriving, on the car's own range: the range is what the charge
         * drives, so the trip takes the same share of both.
         *
         * shortcut: linear in the car's estimate and zero at 0 %, so a buffer the car keeps below
         * its range or a hilly road shows here as error; the plan's arrival model would cover both.
         */
        internal fun arrivalSoc(socPercent: Double, rangeKm: Double, km: Double): Double =
            socPercent * (rangeKm - km) / rangeKm

        /** The companion's extras: the roads the plan offered and the one being driven. */
        fun intent(roads: List<SplitRoad>, chosen: Int, toStop: Boolean, routeKm: Double?): Intent =
            Intent()
                .putExtra(EXTRA_TO_STOP, toStop)
                .putExtra(EXTRA_ROUTE_KM, routeKm ?: Double.NaN)
                .putExtra(EXTRA_NAMES, roads.map { it.name }.toTypedArray())
                .putExtra(EXTRA_KM, roads.map { it.km }.toDoubleArray())
                .putExtra(EXTRA_MINUTES, roads.map { it.minutes }.toDoubleArray())
                // NaN stands for "no comparison": a bundle carries no nullable double array.
                .putExtra(EXTRA_CHARGE_LOW, roads.map { it.chargeLow ?: Double.NaN }.toDoubleArray())
                .putExtra(EXTRA_CHARGE_HIGH, roads.map { it.chargeHigh ?: Double.NaN }.toDoubleArray())
                .putExtra(EXTRA_CHOSEN, chosen)

        internal fun roads(intent: Intent): List<SplitRoad> {
            val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: return emptyList()
            val km = intent.getDoubleArrayExtra(EXTRA_KM) ?: return emptyList()
            val minutes = intent.getDoubleArrayExtra(EXTRA_MINUTES) ?: return emptyList()
            val low = intent.getDoubleArrayExtra(EXTRA_CHARGE_LOW) ?: return emptyList()
            val high = intent.getDoubleArrayExtra(EXTRA_CHARGE_HIGH) ?: return emptyList()
            if (setOf(names.size, km.size, minutes.size, low.size, high.size).size != 1) return emptyList()
            return names.indices.map {
                SplitRoad(
                    names[it],
                    km[it],
                    minutes[it],
                    low[it].takeUnless(Double::isNaN),
                    high[it].takeUnless(Double::isNaN),
                )
            }
        }
    }
}
