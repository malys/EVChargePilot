package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EcoScore
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
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
    private lateinit var pane: MapPane

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-split")
    }

    private var recorder: TripRecordingService? = null
    private var handedBack = false
    private var guidanceReadAtMs = 0L
    private var remainingKm: Double? = null
    private var toStopKm: Double? = null
    private var drivenBaselineKm: Double? = null

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
        super.onCreate(savedInstanceState)
        binding = ActivityNavSplitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.splitFullMap.setOnClickListener { handBack() }
        // Back hands the map back, as the button does.
        onBackPressedDispatcher.addCallback(this) { handBack() }
        renderRoads(roads(intent), intent.getIntExtra(EXTRA_CHOSEN, 0))

        pane = MapPane(this)
        val map = MapApps.comeForward(this)
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
                binding.mapPane.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                pane.start(map) { handBack() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        recorder?.clearListener(this)
        recorder = null
        unbindService(connection)
        super.onStop()
    }

    override fun onDestroy() {
        // A finish nobody asked for, the system's or a crash path's, still gives the map back.
        if (!handedBack) MapApps.toForeground(this)
        pane.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    /** The map back on the main display, full screen, and this screen gone. */
    private fun handBack() {
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
        // Several binder reads per refresh: every few seconds is enough for a remaining distance.
        if (value.timestampMs - guidanceReadAtMs < GUIDANCE_REFRESH_MS) return
        guidanceReadAtMs = value.timestampMs
        worker.execute {
            val guidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
            val km = guidance?.remainingDistanceKm(FirmwareInfo.getGeneration())
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
                    else -> getString(
                        R.string.nav_split_route,
                        String.format(Locale.getDefault(), "%.1f", left),
                        guidance?.remainingMinutes?.toString() ?: unavailable,
                    )
                }
                binding.splitRoad.text = guidance?.road.orEmpty()
                renderReach(recorder?.latest?.rangeKm?.toDouble())
            }
        }
    }

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
            return
        }
        val short = km > rangeKm
        binding.splitReach.show(
            listOf(
                BarGaugeView.Span(0f, (km / rangeKm).toFloat(), if (short) R.color.ev_error else R.color.ev_accent),
            ),
        )
        binding.splitMargin.text = if (short) getString(R.string.nav_split_margin_short) else getString(
            R.string.nav_split_margin,
            String.format(Locale.getDefault(), "%.0f", rangeKm - km),
        )
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
        private const val PERCENT = 100f
        private const val ECO_WARN_BELOW = 50
        private const val SPACING_DP = 8f
        private const val ROAD_BAR_DP = 8f
        private const val DASH = "—"

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
