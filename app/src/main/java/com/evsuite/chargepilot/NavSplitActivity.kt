package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * CP-094: the car's navigation on two thirds of the screen, the companion on the last third.
 *
 * Opened by the "Aller à" handoff in place of the full-screen map, and only where [MapPane.possible]
 * holds. If the pane cannot start the map, this screen hands the map back full screen and
 * closes, which is what the handoff did before this screen existed.
 *
 * **Leaving gives the map back.** Android 9 destroys what is left on a virtual display when the
 * display goes, and the map on it is the guidance the driver follows. So every exit, the
 * button, Back or a finish, first brings the map forward on the main display and only then
 * releases the pane.
 *
 * **The map moves only once it is guiding.** Starting MG4 Navigator on the pane moves its one
 * task off the main display. The 2026-10-19 drive did that while the car was still routing the
 * destination just handed over: the screen went black, then stayed on a frozen *MG4 Navigator*
 * while the guidance carried on. So the pane waits for the car to say it is guiding, then a
 * moment more, and shows a line meanwhile.
 *
 * **The map must be the resumed activity.** Android 9 resumes one activity on the whole head
 * unit, the top of the focused display; the multi-resume that keeps an embedded app live arrived
 * in Android 10. When this screen holds the focus, the map on the pane is paused and stops
 * drawing: one frozen frame (2026-10-19), or black when the move came first (2026-10-21). So
 * whenever this screen is resumed while the pane is up, the map is sent to the front of the
 * pane's display again. This screen still draws while paused and its button still takes touches.
 * If the pane still shows nothing, [MapPane.watchBlack] hands the map back full screen.
 *
 * The guidance is read through the synchronous getters, like the arrival screen reads it. This
 * screen never registers with the adapter's listener.
 */
class NavSplitActivity : AppCompatActivity() {

    /** One road the plan offered, as the split screen draws it. */
    data class SplitRoad(
        val name: String,
        val km: Double,
        val minutes: Double,
        /** Charge against the routed road, signed as cost; null on the routed road itself. */
        val chargeLow: Double?,
        val chargeHigh: Double?,
    )

    private lateinit var binding: ActivityNavSplitBinding

    /**
     * Debug builds only: the companion without the car's map, so the emulator — which has no
     * MG4 Navigator and cannot host the pane — can show the layout (`mise run split-preview`).
     */
    private val preview by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_PREVIEW, false)
    }
    private lateinit var pane: MapPane

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-split")
    }

    private var recorder: TripRecordingService? = null
    private var handedBack = false
    private var map: Intent? = null
    private var paneStartedAt = 0L
    private var paneDue = false
    private var refrontedAt = 0L
    private var guidanceReadAtMs = 0L
    private var remainingKm: Double? = null
    private var toStopKm: Double? = null
    private var drivenBaselineKm: Double? = null
    private var traceReadAtMs = 0L
    private val consumption by lazy { RecentConsumption(this) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = bound
            bound.setListener(this@NavSplitActivity, ::render)
            bound.latest?.let(::render)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always the night palette, day or not: the companion sits beside the car's own map,
        // dark in both modes, and a white quarter beside it is the brightest thing on the panel.
        // An exception to DESIGN.md's DayNight rule, agreed by the owner on 2026-10-22; the night
        // palette carries the same 7:1 floor as the day one.
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        binding = ActivityNavSplitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.splitFullMap.setOnClickListener { handBack() }
        // Back hands the map back, as the button does.
        onBackPressedDispatcher.addCallback(this) { handBack() }
        renderRoads(
            roads(intent).ifEmpty { if (preview) PREVIEW_ROADS else emptyList() },
            intent.getIntExtra(EXTRA_CHOSEN, 0),
        )
        if (preview) {
            binding.mapWaiting.setText(R.string.nav_split_preview_map)
            return
        }

        pane = MapPane(this)
        val map = MapApps.comeForward(this).also { this.map = it }
        val view = pane.view
        if (map == null || view == null) {
            handBack()
            return
        }
        worker.execute {
            // shutdownNow interrupts the wait when the screen goes first: nothing left to start.
            val waitedMs = try { waitForGuidance() } catch (e: InterruptedException) { return@execute }
            runOnUiThread {
                if (handedBack || isDestroyed) return@runOnUiThread
                AppLogger.i(TAG, "map pane: starting after ${waitedMs} ms of routing")
                // A stopped screen never gets the surface the pane's display is built on: on
                // 2026-10-22 the map had come forward full screen 0.2 s before, and five seconds
                // of retries ended in "before ActivityView is ready". So it waits for onStart.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPane()
                else paneDue = true.also { AppLogger.i(TAG, "map pane: host stopped, waits for it") }
            }
        }
    }

    private fun startPane() {
        val intent = map ?: return
        val view = pane.view ?: return
        paneDue = false
        binding.mapPane.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        pane.start(intent, onStarted = ::paneStarted) { handBack() }
    }

    private fun paneStarted() {
        paneStartedAt = SystemClock.elapsedRealtime()
        pane.watchBlack(BLACK_LIMIT_MS) { reason ->
            AppLogger.w(TAG, "map pane: $reason after ${BLACK_LIMIT_MS} ms, map back full screen")
            handBack()
        }
        // Launching on the pane should move the focus there and pause this screen. If it did not,
        // onResume will not come again, so check once.
        binding.root.postDelayed(
            { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refront("still resumed") },
            REFRONT_CHECK_MS,
        )
    }

    override fun onResume() {
        super.onResume()
        if (paneStartedAt != 0L) refront("resumed")
    }

    override fun onPause() {
        if (paneStartedAt != 0L) {
            AppLogger.i(TAG, "map pane: host paused ${SystemClock.elapsedRealtime() - paneStartedAt} ms after start")
        }
        super.onPause()
    }

    /**
     * This screen has the focus, so the map on the pane is paused: send it to the front again.
     * MAIN/HOME to the running task is the vendor launcher's "come forward", never a second copy.
     * At most once per [REFRONT_MIN_MS], so two displays trading the focus cannot spin.
     */
    private fun refront(why: String) {
        val intent = map ?: return
        val now = SystemClock.elapsedRealtime()
        if (handedBack || now - refrontedAt < REFRONT_MIN_MS) return
        refrontedAt = now
        AppLogger.i(TAG, "map pane: host $why ${now - paneStartedAt} ms after start, map to front")
        pane.start(intent) { handBack() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (paneDue && !handedBack) startPane()
    }

    override fun onStop() {
        recorder?.clearListener(this)
        recorder = null
        unbindService(connection)
        super.onStop()
    }

    override fun onDestroy() {
        // A finish nobody asked for, the system's or a crash path's, still gives the map back.
        if (!handedBack && !preview) MapApps.toForeground(this)
        if (::pane.isInitialized) pane.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** The map back on the main display, full screen, and this screen gone. */
    private fun handBack() {
        if (preview) return finish()
        if (handedBack) return
        handedBack = true
        MapApps.toForeground(this)
        finish()
    }

    /**
     * Blocks the worker until the car says it is guiding, or gives up; returns the wait.
     *
     * shortcut: the settle is a guess at how long the map needs after the flag turns true; a
     * drive whose bundle still shows the freeze is the cue to lengthen it or to watch the map's
     * own window instead.
     */
    private fun waitForGuidance(): Long {
        val startedAt = SystemClock.elapsedRealtime()
        repeat(GUIDANCE_POLLS) {
            if (runCatching { SaicNavGuidance.isMapNavigating() }.getOrNull() == true) {
                Thread.sleep(GUIDANCE_SETTLE_MS)
                return SystemClock.elapsedRealtime() - startedAt
            }
            Thread.sleep(GUIDANCE_POLL_MS)
        }
        return SystemClock.elapsedRealtime() - startedAt
    }

    private fun render(value: EnergySnapshot) {
        val unavailable = getString(R.string.value_unavailable)
        val soc = value.socPercent?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: unavailable
        binding.splitSocRing.show(value.socPercent?.div(PERCENT), soc)
        binding.splitSocRing.contentDescription = getString(R.string.nav_split_battery, soc)
        binding.splitRange.text =
            value.rangeKm?.let { String.format(Locale.getDefault(), "%.0f km", it) } ?: unavailable
        renderReach(value.rangeKm?.toDouble())
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
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val toStop = intent.getBooleanExtra(EXTRA_TO_STOP, false)
                val routeKm = intent.getDoubleExtra(EXTRA_ROUTE_KM, Double.NaN).takeUnless(Double::isNaN)
                remainingKm = destinationKm(toStop, km, routeKm, drivenKm(value))
                toStopKm = km.takeIf { toStop }
                val left = remainingKm
                binding.splitRoute.text = when {
                    left == null -> getString(R.string.nav_split_no_guidance)
                    toStop -> getString(
                        R.string.nav_split_route_stop,
                        String.format(Locale.getDefault(), "%.0f", left),
                        toStopKm?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: unavailable,
                    )
                    minutes != null -> getString(
                        R.string.nav_split_route_eta,
                        String.format(Locale.getDefault(), "%.1f", left),
                        minutes.toString(),
                        arrivalClock(minutes),
                    )
                    else -> getString(
                        R.string.nav_split_route,
                        String.format(Locale.getDefault(), "%.1f", left),
                        unavailable,
                    )
                }
                binding.splitRoad.text = road.orEmpty()
                renderReach(recorder?.latest?.rangeKm?.toDouble())
            }
        }
    }

    /** The clock time the car's minutes left come to, in the head unit's own 12/24 h format. */
    private fun arrivalClock(minutes: Int): String =
        DateFormat.getTimeFormat(this).format(Date(System.currentTimeMillis() + minutes * 60_000L))

    /**
     * Distance driven since this screen opened, which is the tap.
     *
     * shortcut: a trip that ends and restarts mid-route resets the count, so the destination reads
     * further than it is; the adapter's odometer would survive that, at a whole kilometre per step.
     */
    private fun drivenKm(value: EnergySnapshot): Double {
        val trip = EnergyTripSession.current(value.timestampMs)?.recordedDistanceKm ?: 0.0
        val baseline = drivenBaselineKm ?: trip.also { drivenBaselineKm = it }
        return (trip - baseline).coerceAtLeast(0.0)
    }

    /** The trip's share of the range on the bar, and what is left of the range on arriving. */
    private fun renderReach(rangeKm: Double?) {
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
        binding.splitMargin.text = if (short) getString(R.string.nav_split_margin_short) else getString(
            R.string.nav_split_margin,
            String.format(Locale.getDefault(), "%.0f", rangeKm - km),
        )
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
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val average = recent.consumptionKwhPer100Km
                val reference = recent.referenceKwhPer100Km
                binding.splitTrace.setTrace(recent.trace, reference, recent.coveredKm)
                binding.splitArc.show(
                    average,
                    reference,
                    arcScale(average, reference),
                    if (average != null && average > reference) R.color.ev_warn else R.color.ev_accent,
                )
                binding.splitConsumption.text = average
                    ?.let { String.format(Locale.getDefault(), "%.1f", it) }
                    ?: getString(R.string.value_unavailable)
                binding.splitArc.contentDescription = average
                    ?.let { getString(R.string.nav_split_consumption_value, it) }
                binding.splitConsumptionSpan.text = if (recent.consumptionKwhPer100Km == null) {
                    ""
                } else {
                    getString(R.string.nav_split_consumption_span, recent.coveredKm)
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
        binding.splitEcoBand.text = recorder?.eco?.band(verdict) ?: getString(R.string.value_unavailable)
        if (score == null) {
            binding.splitEcoRing.show(null, getString(R.string.value_unavailable))
            binding.splitEcoParts.text = getString(R.string.nav_split_eco_waiting)
            return
        }
        binding.splitEcoRing.show(
            score.total / PERCENT,
            score.total.toString(),
            if (score.total < ECO_WARN_BELOW) R.color.ev_warn else R.color.ev_accent,
        )
        binding.splitEcoParts.text = getString(
            R.string.nav_split_eco_parts,
            score.anticipation?.toString() ?: DASH,
            score.smoothness?.toString() ?: DASH,
            score.efficiency?.toString() ?: DASH,
        )
    }

    /** One line and one bar per road; the bar's length is the road's time against the longest. */
    private fun renderRoads(roads: List<SplitRoad>, chosen: Int) {
        if (roads.size < 2) return
        val fastest = roads.minOf { it.minutes }
        val longest = roads.maxOf { it.minutes }.takeIf { it > 0.0 } ?: return
        val density = resources.displayMetrics.density
        roads.forEachIndexed { at, road ->
            val delta = road.minutes - fastest
            var line = getString(
                R.string.route_choices_road,
                road.name,
                String.format(Locale.getDefault(), "%.0f", road.km),
                String.format(Locale.getDefault(), "%.0f", road.minutes),
                if (delta < 0.5) getString(R.string.route_choices_delta_fastest)
                else getString(R.string.route_choices_delta_slower, String.format(Locale.getDefault(), "%.0f", delta)),
            )
            if (at == chosen) line = getString(R.string.route_choices_chosen, line)
            binding.splitRoads.addView(
                TextView(this).apply {
                    setTextAppearance(if (at == chosen) R.style.Text_EV_Body else R.style.Text_EV_Secondary)
                    text = if (road.chargeLow == null || road.chargeHigh == null) line else line + "\n" + getString(
                        R.string.nav_split_road_charge,
                        String.format(Locale.getDefault(), "%+.1f", road.chargeLow),
                        String.format(Locale.getDefault(), "%+.1f", road.chargeHigh),
                    )
                    setPadding(0, (SPACING_DP * density).toInt(), 0, 0)
                },
            )
            binding.splitRoads.addView(
                BarGaugeView(this).apply {
                    show(
                        listOf(
                            BarGaugeView.Span(
                                0f,
                                (road.minutes / longest).toFloat(),
                                R.color.ev_accent,
                                if (at == chosen) BarGaugeView.OPAQUE else BarGaugeView.LIGHT,
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
        private const val TAG = "EVChargePilot"
        private const val GUIDANCE_REFRESH_MS = 5_000L
        private const val GUIDANCE_POLLS = 20
        private const val GUIDANCE_POLL_MS = 500L
        private const val GUIDANCE_SETTLE_MS = 2_000L
        private const val BLACK_LIMIT_MS = 8_000L
        private const val REFRONT_CHECK_MS = 1_000L
        private const val REFRONT_MIN_MS = 3_000L
        private const val PERCENT = 100f
        private const val ARC_STEP = 5.0
        private const val ECO_WARN_BELOW = 50
        private const val SPACING_DP = 8f
        private const val ROAD_BAR_DP = 8f
        private const val DASH = "—"
        private const val TRACE_REFRESH_MS = 10_000L

        private const val EXTRA_PREVIEW = "split.preview"
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

        /** The split screen, carrying the roads the plan offered and the one being driven. */
        fun intent(context: Context, roads: List<SplitRoad>, chosen: Int, toStop: Boolean, routeKm: Double?): Intent =
            Intent(context, NavSplitActivity::class.java)
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
