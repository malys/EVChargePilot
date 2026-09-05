package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivitySpeedWhatIfBinding
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Parked-only explanation of modelled motorway speed alternatives for one completed trip. */
class SpeedWhatIfActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySpeedWhatIfBinding
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-speed-what-if")
    }
    private var recorder: TripRecordingService? = null
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null
    private var bound = false
    private var loaded = false
    private var result: SpeedWhatIfResult? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@SpeedWhatIfActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                renderGate()
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            renderGate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            speedKmh = null
            speedObservedAtMs = null
            renderGate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpeedWhatIfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        val startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
        if (startedAtMs == INVALID_ID) {
            finish()
            return
        }
        load(startedAtMs)
        renderGate()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) renderGate()
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
            val evidence = trip?.summary?.batteryPowerEvidence
            val model = LocalEnergyModel.loadOrTrain(filesDir, trips, evidence)
            // The charge fit, for the car that publishes no power — which is this one.
            val socModel = (SocConsumptionFitter().fit(trips) as? SocConsumptionFitResult.Ready)
                ?.model
            result = trip?.let { SpeedWhatIfCalculator.calculate(it, model, socModel) }
                ?: SpeedWhatIfResult.Unavailable(SpeedWhatIfUnavailable.NO_MOTORWAY_PORTION)
            loaded = true
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderGate()
            }
        }
    }

    private fun renderGate() {
        binding.root.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        when (ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)) {
            ParkedDeletionGate.MOVING -> renderBlocked(R.string.speed_what_if_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE -> renderBlocked(
                R.string.speed_what_if_speed_unavailable,
            )
            ParkedDeletionGate.PARKED -> {
                val ageMs = nowMs - checkNotNull(speedObservedAtMs)
                binding.root.postDelayed(
                    gateExpiry,
                    ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
                )
                if (!loaded) renderBlocked(R.string.speed_what_if_loading) else renderResult()
            }
        }
    }

    private fun renderBlocked(message: Int) {
        binding.whatIfStatus.setText(message)
        binding.whatIfResults.text = ""
        binding.whatIfResults.visibility = View.GONE
    }

    private fun renderResult() {
        when (val current = result) {
            is SpeedWhatIfResult.Ready -> {
                binding.whatIfResults.visibility = View.VISIBLE
                val charge = current.basis == SpeedWhatIfBasis.STATE_OF_CHARGE_PERCENT
                binding.whatIfStatus.text = getString(
                    if (charge) R.string.speed_what_if_ready_charge else R.string.speed_what_if_ready,
                    current.motorwayDistanceKm,
                )
                // The unit is written on every figure, because "4" in percent and "4" in
                // kilowatt-hours are not the same drive told twice.
                val unit = if (charge) "%" else "kWh"
                binding.whatIfResults.text = current.comparisons.joinToString("\n\n") { row ->
                    getString(
                        if (charge) R.string.speed_what_if_row_charge else R.string.speed_what_if_row,
                        row.referenceSpeedKmh,
                        range(row.modelledLow, row.modelledHigh, unit),
                        range(row.deltaLow, row.deltaHigh, unit),
                        range(row.rangeDeltaLowKm, row.rangeDeltaHighKm, "km"),
                    )
                }
            }
            is SpeedWhatIfResult.Unavailable -> renderBlocked(reason(current.reason))
            null -> renderBlocked(R.string.reason_model_not_trained)
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

    private fun range(low: Double, high: Double, unit: String): String =
        String.format(Locale.getDefault(), "≈ %.2f–%.2f %s", low, high, unit)

    companion object {
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE
        private const val HISTORY_FILE = "trips.json"

        fun forTrip(context: Context, startedAtMs: Long) =
            Intent(context, SpeedWhatIfActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)
    }

    private val gateExpiry = Runnable { renderGate() }
}
