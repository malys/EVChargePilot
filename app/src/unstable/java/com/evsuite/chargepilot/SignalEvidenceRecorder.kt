package com.evsuite.chargepilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.telemetry.EnergyTelemetryReader
import com.evsuite.hardware.telemetry.EvidenceCapture
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicHub
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.telemetry.TelemetryEvidenceRecorder

/**
 * Signal statistics for the whole session, without anyone starting a capture.
 *
 * The manual capture screen answers the same question, but only for the window somebody
 * remembered to open it — and the questions still outstanding are about what a signal does
 * *on a drive*: whether battery power ever publishes, and what `PERF_VEHICLE_SPEED` actually
 * ranges over. A trip recorded 7 km for a 2.4 km drive, which is the m/s-to-km/h factor, and
 * the only thing that settles it is the maximum speed this recorder saw against a speedometer
 * the driver was reading anyway.
 *
 * **Cost.** One property read a second, the same cadence the recording service already uses,
 * and constant memory: each signal keeps running min, max, mean, null and sign counts, never
 * the samples. Unstable only.
 */
internal object SignalEvidenceRecorder {

    private const val TAG = "EVChargePilot"
    private const val TICK_MS = 1_000L

    /** Deliberately unlike the snapshot's `odometer_km`: a different service, a different read. */
    private const val ADAPTER_ODOMETER_KM = "nav_adapter_odometer_km"

    /**
     * The charging service's own counters, under names that cannot be read as properties.
     *
     * `saic_` because none of these is a vehicle property: they come from the charging
     * service's binder, and a bundle mixing the two would let a later session attribute a
     * reading to an interface that never produced it.
     */
    private const val CHARGING_CONSUMED_KWH = "saic_consumed_kwh_since_start"
    private const val CHARGING_ACC_KWH = "saic_acc_counter_kwh_since_start"
    private const val CHARGING_REGEN_KWH = "saic_regen_kwh_since_start"
    private const val CHARGING_PER_KM = "saic_consumption_per_km"

    private val ticker = Handler(Looper.getMainLooper())
    private var reader: EnergyTelemetryReader? = null

    /** Guarded by itself: the tick writes on the main thread, an export reads on its own. */
    private val recorder = TelemetryEvidenceRecorder()

    @Volatile
    private var running = false

    @Volatile
    private var startedAtMs: Long? = null

    val isRunning: Boolean get() = running

    /** When this session's recording began, so a caller can tell which trips belong to it. */
    val sessionStartedAtMs: Long? get() = startedAtMs

    /**
     * How far the adapter odometer moved during this session, or null when it never read.
     *
     * This is a distance nothing derived from speed, which is the whole point: see
     * [SpeedScaleCheck].
     */
    fun adapterOdometerSpanKm(): Double? {
        val evidence = capture().signals.firstOrNull { it.signal == ADAPTER_ODOMETER_KM }
            ?: return null
        val min = evidence.min ?: return null
        val max = evidence.max ?: return null
        return (max - min).takeIf { it >= 0.0 }
    }

    fun start(context: Context) {
        if (running) return
        reader = EnergyTelemetryReader(context.applicationContext)
        SaicNav.connect(context.applicationContext)
        // The charging counters come through the vehicle hub, and binding it is asynchronous:
        // connect at start so the first ticks are reading a bound service rather than
        // recording nulls that look exactly like a car that publishes nothing.
        SaicHub.connect(context.applicationContext)
        startedAtMs = System.currentTimeMillis()
        running = true
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stop() {
        ticker.removeCallbacks(tick)
        running = false
    }

    fun capture(): EvidenceCapture = synchronized(recorder) { recorder.capture() }

    /**
     * The odometer as the navigation adapter reports it, which is not where the snapshot's
     * odometer comes from.
     *
     * `PERF_ODOMETER` answers nothing on this car, so a trip distance has had no independent
     * check at all: it is integrated from speed, and if the speed scale is wrong the distance
     * is wrong by exactly the same factor with nothing to contradict it. `SaicNav` reads a
     * different service entirely, and its total mileage over a drive is a distance nobody
     * derived from speed. The difference between the two settles the unit question without
     * anyone watching a speedometer.
     *
     * Recorded under its own name so it is never mistaken for the vehicle property.
     */
    private fun recordAdapterOdometer() {
        val km = runCatching { SaicNav.totalMileageKm() }
            .onFailure { AppLogger.d(TAG, "adapter odometer failed: ${it.message}") }
            .getOrNull()
        synchronized(recorder) {
            recorder.record(ADAPTER_ODOMETER_KM, km?.toDouble(), System.currentTimeMillis())
        }
    }

    /**
     * The kilowatt-hours the car counted for itself, which no vehicle property publishes.
     *
     * This is what the bundle is carrying them for: `TotalConsumptionAfterStart` and
     * `AccConsumptionAfterStart` are both counters over the same period, and whether `Acc` is
     * the *accessory* share of the total or merely a second *accumulated* framing of it cannot
     * be settled at a desk — the service bounds them identically and names neither. Sampled
     * side by side across a drive with the climate off and a drive with it on, the two
     * hypotheses look nothing alike, and the min-to-max span of each answers it.
     *
     * Recorded as raw counters, not as differences. They reset at ignition-on, so a drop
     * inside one session is a reset and not regeneration; keeping the raw series means a later
     * reader can see the reset instead of inheriting this one's interpretation of it.
     */
    private fun recordChargingCounters() {
        val nowMs = System.currentTimeMillis()
        record(CHARGING_CONSUMED_KWH, nowMs) { SaicCharging.consumedKwhSinceStart() }
        record(CHARGING_ACC_KWH, nowMs) { SaicCharging.accCounterKwhSinceStart() }
        record(CHARGING_REGEN_KWH, nowMs) { SaicCharging.regeneratedKwhSinceStart() }
        record(CHARGING_PER_KM, nowMs) { SaicCharging.consumptionPerKm() }
    }

    /** A read that throws is a reading we do not have, recorded as the null it is. */
    private fun record(signal: String, atMs: Long, read: () -> Float?) {
        val value = runCatching(read)
            .onFailure { AppLogger.d(TAG, "$signal failed: ${it.message}") }
            .getOrNull()
        synchronized(recorder) { recorder.record(signal, value?.toDouble(), atMs) }
    }

    private val tick = object : Runnable {
        override fun run() {
            val source = reader
            if (source != null) {
                // A read that throws is a reading we do not have, not a reason to stop the
                // session's statistics: the next second is tried exactly the same way.
                runCatching { source.readEvidence(System.currentTimeMillis()) }
                    .onFailure { AppLogger.d(TAG, "signal evidence sample failed: ${it.message}") }
                    .getOrNull()
                    ?.let { sample -> synchronized(recorder) { recorder.record(sample) } }
            }
            recordAdapterOdometer()
            recordChargingCounters()
            ticker.postDelayed(this, TICK_MS)
        }
    }
}
