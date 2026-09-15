package com.evsuite.chargepilot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityTripPlotBinding
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.TripSample
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One trip's trace at full size, with a cursor that reads the record back sample by sample.
 *
 * The ledger's plot is a thumbnail a couple of hundred pixels tall, and on the car it is the
 * first thing a shorter panel takes space from. Rather than fight for those pixels in the
 * ledger, the trace gets a screen of its own where the axes can be labelled and a value can be
 * read off the curve instead of estimated from it.
 *
 * Nothing here is computed. Every figure is the sample as it was recorded, so a gap in the
 * record reads as a gap and not as a zero.
 */
class TripPlotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripPlotBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-trip-plot")
    }
    private var samples: List<TripSample> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripPlotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.tripPlot.detailed = true
        binding.tripPlot.onScrub = ::renderCursor
        renderCursor(null)

        val startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
        if (startedAtMs == INVALID_ID) {
            finish()
            return
        }
        binding.plotTitle.text = DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(startedAtMs))
        load(startedAtMs)
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun load(startedAtMs: Long) {
        disk.execute {
            val trip = EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).read()
                .firstOrNull { it.summary.startedAtMs == startedAtMs }
            val track = trip?.samples.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                samples = track
                binding.tripPlot.setSamples(track)
                val minutes = if (track.isEmpty()) 0L else {
                    (track.last().atMs - track.first().atMs).coerceAtLeast(0L) / 60_000L
                }
                binding.tripPlot.contentDescription =
                    getString(R.string.trip_track_description, minutes)
                binding.plotLegend.text = if (track.isEmpty()) {
                    getString(R.string.trip_track_unavailable)
                } else {
                    getString(R.string.trip_plot_legend)
                }
            }
        }
    }

    /**
     * The cursor readout, or the trip's own extremes when nothing is under the finger.
     *
     * A resting state of six dashes would say the record is empty when it is not. With no
     * cursor the row shows what the whole trace covers, which is also what the labels on the
     * axes are scaled to.
     */
    private fun renderCursor(sample: TripSample?) {
        if (sample == null) {
            val speeds = samples.mapNotNull { it.speedKmh }
            val socs = samples.mapNotNull { it.socPercent }
            val temps = samples.mapNotNull { it.outsideTempCelsius }
            val powers = samples.mapNotNull { it.batteryPowerKw }
            binding.cursorTime.text = samples.takeIf { it.isNotEmpty() }?.let {
                TripPlotView.elapsed(it.last().atMs - it.first().atMs)
            } ?: DASH
            binding.cursorSpeed.text = speeds.maxOrNull()?.let { format(PATTERN_SPEED, it) } ?: DASH
            binding.cursorSoc.text = if (socs.isEmpty()) DASH else String.format(
                Locale.getDefault(), "%.1f → %.1f %%", socs.first(), socs.last(),
            )
            binding.cursorOutsideTemp.text =
                temps.maxOrNull()?.let { format(PATTERN_TEMP, it) } ?: DASH
            binding.cursorPower.text =
                powers.maxOrNull()?.let { format(PATTERN_POWER, it) } ?: DASH
            binding.cursorClimate.text = climate(samples.lastOrNull())
            return
        }
        binding.cursorTime.text = TripPlotView.elapsed(sample.atMs - samples.first().atMs)
        binding.cursorSpeed.text = sample.speedKmh?.let { format(PATTERN_SPEED, it) } ?: DASH
        binding.cursorSoc.text = sample.socPercent?.let { format(PATTERN_SOC, it) } ?: DASH
        binding.cursorOutsideTemp.text =
            sample.outsideTempCelsius?.let { format(PATTERN_TEMP, it) } ?: DASH
        binding.cursorPower.text = sample.batteryPowerKw?.let { format(PATTERN_POWER, it) } ?: DASH
        binding.cursorClimate.text = climate(sample)
    }

    /** Climate is three signals; the fan level is the one that separates idling from working. */
    private fun climate(sample: TripSample?): String {
        if (sample == null) return DASH
        val on = sample.climatePowerOn ?: return DASH
        if (!on) return getString(R.string.state_off)
        val fan = sample.climateFanLevel
        val ac = sample.climateAcOn == true
        val prefix = getString(if (ac) R.string.trip_plot_climate_ac else R.string.state_on)
        return if (fan == null) prefix else "$prefix · $fan"
    }

    private fun format(pattern: String, value: Float) =
        String.format(Locale.getDefault(), pattern, value)

    companion object {
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE
        private const val HISTORY_FILE = "trips.json"
        private const val DASH = "—"
        private const val PATTERN_SPEED = "%.0f km/h"
        private const val PATTERN_SOC = "%.1f %%"
        private const val PATTERN_TEMP = "%.0f °C"
        private const val PATTERN_POWER = "%.1f kW"

        fun forTrip(context: Context, startedAtMs: Long): Intent =
            Intent(context, TripPlotActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)
    }
}
