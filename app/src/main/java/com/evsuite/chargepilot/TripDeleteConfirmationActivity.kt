package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityTripDeleteConfirmationBinding
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import java.io.File
import java.util.concurrent.Executors

/** Full-screen, parked-only confirmation for irreversible local history deletion. */
class TripDeleteConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripDeleteConfirmationBinding
    private lateinit var store: EnergyTripHistoryStore
    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-history-delete")
    }
    private var recorder: TripRecordingService? = null
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null
    private var bound = false
    private var deleteAll = false
    private var startedAtMs = INVALID_ID

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener { snapshot ->
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
        binding = ActivityTripDeleteConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = EnergyTripHistoryStore(File(filesDir, "trips.json"))
        deleteAll = intent.getBooleanExtra(EXTRA_ALL, false)
        startedAtMs = intent.getLongExtra(EXTRA_STARTED_AT, INVALID_ID)
        if (!deleteAll && startedAtMs == INVALID_ID) {
            finish()
            return
        }

        binding.confirmationTitle.setText(
            if (deleteAll) R.string.trip_delete_all_title else R.string.trip_delete_one_title
        )
        binding.confirmationMessage.setText(
            if (deleteAll) R.string.trip_delete_all_message else R.string.trip_delete_one_message
        )
        binding.cancelAction.setOnClickListener { finish() }
        binding.confirmAction.setOnClickListener { deleteIfParked() }
        renderGate()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        if (!bound) renderGate()
    }

    override fun onStop() {
        recorder?.setListener(null)
        recorder = null
        binding.confirmAction.removeCallbacks(gateExpiry)
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

    private fun renderGate() {
        binding.confirmAction.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)
        binding.confirmAction.isEnabled = gate == ParkedDeletionGate.PARKED
        if (gate == ParkedDeletionGate.PARKED) {
            val ageMs = nowMs - checkNotNull(speedObservedAtMs)
            binding.confirmAction.postDelayed(
                gateExpiry,
                ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
            )
        }
        binding.gateStatus.setText(
            when (gate) {
                ParkedDeletionGate.PARKED -> R.string.trip_delete_ready
                ParkedDeletionGate.MOVING -> R.string.trip_delete_moving
                ParkedDeletionGate.SPEED_UNAVAILABLE -> R.string.trip_delete_speed_unavailable
            }
        )
    }

    /** A disabled button is presentation; this recheck is the destructive-action boundary. */
    private fun deleteIfParked() {
        if (ParkedDeletionPolicy.gate(
                speedKmh,
                speedObservedAtMs,
                System.currentTimeMillis(),
            ) != ParkedDeletionGate.PARKED
        ) {
            renderGate()
            return
        }
        binding.confirmAction.isEnabled = false
        binding.cancelAction.isEnabled = false
        disk.execute {
            val success = if (deleteAll) store.clear() else store.deleteTrip(startedAtMs)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (success) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    binding.cancelAction.isEnabled = true
                    renderGate()
                    binding.gateStatus.setText(R.string.trip_delete_failed)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ALL = "delete_all"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val INVALID_ID = Long.MIN_VALUE

        fun single(context: Context, startedAtMs: Long) =
            Intent(context, TripDeleteConfirmationActivity::class.java)
                .putExtra(EXTRA_STARTED_AT, startedAtMs)

        fun all(context: Context) = Intent(context, TripDeleteConfirmationActivity::class.java)
            .putExtra(EXTRA_ALL, true)
    }

    private val gateExpiry = Runnable { renderGate() }
}
