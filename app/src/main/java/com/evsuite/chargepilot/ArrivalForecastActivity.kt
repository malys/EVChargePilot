package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityArrivalForecastBinding
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.ArrivalSocForecast
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.SocRate
import com.evsuite.hardware.telemetry.SocRateEstimator
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What the charge will read on arrival, if the head unit knows where the driver is going.
 *
 * The obvious way to build this — the energy model, a distance, a pack capacity — cannot work
 * on this vehicle: CP-003 proved battery power is declared and never published, so nothing can
 * integrate kWh. State of charge per kilometre needs no energy unit and both its inputs read,
 * which is what this screen shows.
 *
 * **It reads the navigation, it never registers with it.** `SaicNavGuidance.readNow` answers
 * from synchronous getters; the listener registration that CP-040 used to discover them stays
 * in the unstable channel, so a stable build never joins the adapter's callback fan-out.
 *
 * Everything happens on [worker]: the vehicle binder calls, the trip file, and the arithmetic.
 * The dashboard's own sampling must not wait on any of it.
 */
class ArrivalForecastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArrivalForecastBinding
    private lateinit var provenance: ProvenanceText

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chargepilot-arrival")
    }

    @Volatile
    private var snapshot: EnergySnapshot? = null

    /** Read once: the trip file does not change while this screen is open. */
    @Volatile
    private var rate: SocRate? = null

    @Volatile
    private var ratePending = true

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var refresh: ScheduledFuture<*>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            snapshot = value.latest
            value.setListener(this@ArrivalForecastActivity) { latest -> snapshot = latest }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            snapshot = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArrivalForecastBinding.inflate(layoutInflater)
        setContentView(binding.root)
        provenance = ProvenanceText(this)
        binding.backAction.setOnClickListener { finish() }
        worker.execute {
            SaicNavGuidance.connect(applicationContext)
            loadRate()
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        refresh = worker.scheduleWithFixedDelay(::recompute, 0L, REFRESH_SECONDS, TimeUnit.SECONDS)
    }

    override fun onStop() {
        refresh?.cancel(false)
        refresh = null
        recorder?.clearListener(this)
        recorder = null
        if (bound) unbindService(connection)
        bound = false
        snapshot = null
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * The rate this driver has actually spent, when enough trips can be vouched for.
     *
     * Trips recorded before the speed conversion was fixed are 3.6 times too long and are
     * refused by the estimator, so a fresh install has no history and falls back to the
     * vehicle's own range — which is the point of having two sources.
     */
    private fun loadRate() {
        val generation = FirmwareInfo.getGeneration()
        val trips = runCatching {
            EnergyTripHistoryStore(File(filesDir, HISTORY_FILE)).readSummaries()
        }.getOrDefault(emptyList())
        rate = SocRateEstimator.fromTrips(trips, generation)
        ratePending = false
    }

    private fun recompute() {
        val latest = snapshot
        val generation = FirmwareInfo.getGeneration()
        val guidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
        val remainingKm = guidance?.remainingDistanceKm(generation)
        val socPercent = latest?.socPercent?.toDouble()
        val effective = rate ?: SocRateEstimator.fromVehicleRange(
            socPercent,
            latest?.rangeKm?.toDouble(),
        )
        val arrival = ArrivalSocForecast.of(socPercent, remainingKm, effective)
        val reach = ArrivalSocForecast.rangeAtRateKm(socPercent, effective)
        val state = ViewState(
            arrival = arrival,
            reachKm = reach,
            remainingKm = remainingKm,
            minutes = guidance?.remainingMinutes,
            road = guidance?.road,
            rate = effective,
            routeKnown = remainingKm != null,
            ratePending = ratePending,
        )
        runOnUiThread { if (!isFinishing && !isDestroyed) render(state) }
    }

    private fun render(state: ViewState) {
        val arrival = provenance.render(state.arrival, "%.0f %%")
        binding.arrivalValue.text = arrival
        binding.arrivalValue.contentDescription =
            provenance.describe(getString(R.string.arrival_label), state.arrival, arrival)

        binding.arrivalStatus.text = when {
            !state.routeKnown -> getString(R.string.arrival_no_route)
            state.ratePending -> getString(R.string.arrival_rate_loading)
            !state.arrival.isAvailable -> getString(R.string.arrival_refused)
            else -> getString(
                R.string.arrival_route,
                String.format(Locale.getDefault(), "%.1f", state.remainingKm ?: 0.0),
                state.minutes ?: 0,
                state.road.orEmpty(),
            )
        }

        binding.arrivalDetail.visibility = if (state.rate == null) View.GONE else View.VISIBLE
        state.rate?.let { rate ->
            binding.arrivalDetail.text = getString(
                R.string.arrival_detail,
                getString(
                    when (rate.source) {
                        SocRate.Source.TRIP_HISTORY -> R.string.arrival_source_trips
                        SocRate.Source.VEHICLE_RANGE -> R.string.arrival_source_range
                    }
                ),
                rate.sampleCount,
                String.format(Locale.getDefault(), "%.3f", rate.percentPerKm),
                provenance.render(state.reachKm, "%.0f km"),
            )
        }
    }

    private data class ViewState(
        val arrival: Provenanced<Double>,
        val reachKm: Provenanced<Double>,
        val remainingKm: Double?,
        val minutes: Int?,
        val road: String?,
        val rate: SocRate?,
        val routeKnown: Boolean,
        val ratePending: Boolean,
    )

    private companion object {
        const val HISTORY_FILE = "trips.json"

        /** Slow on purpose: a route shortens by metres a second and this is not a speedometer. */
        const val REFRESH_SECONDS = 5L
    }
}
