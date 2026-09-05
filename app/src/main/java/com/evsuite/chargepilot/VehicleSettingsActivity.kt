package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityVehicleSettingsBinding
import java.util.Locale

/**
 * Where the four numbers the app used to assume become the driver's own.
 *
 * Parked-only, like every typing screen in this app, and for the same reason: four numeric
 * fields is a keyboard, and a keyboard at 130 km/h is not a setting, it is a hazard.
 *
 * The screen states what is in force before it offers to change it, and says plainly whether
 * those figures are the driver's or a specification sheet's. A default presented as a
 * measurement is the failure this whole ticket exists to remove.
 */
class VehicleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVehicleSettingsBinding

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null

    /** Set by an action, cleared by the next gate render: the outcome outranks the status. */
    private var message: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@VehicleSettingsActivity) { snapshot ->
                speedKmh = snapshot.speedKmh
                speedObservedAtMs = snapshot.timestampMs
                render()
            }
            speedKmh = value.latest?.speedKmh
            speedObservedAtMs = value.latest?.timestampMs
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
            speedKmh = null
            speedObservedAtMs = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.vehicleSaveAction.setOnClickListener { save() }
        binding.vehicleResetAction.setOnClickListener { reset() }
        fill(VehicleSettings.read(this))
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, TripRecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) render()
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

    private fun save() {
        val parsed = VehicleSettings.parse(
            binding.capacityInput.text?.toString().orEmpty(),
            binding.healthInput.text?.toString().orEmpty(),
            binding.minPowerInput.text?.toString().orEmpty(),
            binding.reserveInput.text?.toString().orEmpty(),
        )
        when (parsed) {
            is VehicleSettings.Parsed.Refused -> announce(
                getString(
                    when (parsed.field) {
                        VehicleSettings.Field.CAPACITY -> R.string.vehicle_refused_capacity
                        VehicleSettings.Field.HEALTH -> R.string.vehicle_refused_health
                        VehicleSettings.Field.MIN_POWER -> R.string.vehicle_refused_min_power
                        VehicleSettings.Field.RESERVE -> R.string.vehicle_refused_reserve
                    }
                )
            )

            is VehicleSettings.Parsed.Ok -> {
                VehicleSettings.write(this, parsed.values)
                fill(parsed.values)
                announce(getString(R.string.vehicle_saved))
            }
        }
    }

    private fun reset() {
        VehicleSettings.clear(this)
        fill(VehicleSettings.read(this))
        announce(getString(R.string.vehicle_reset_done))
    }

    private fun fill(values: VehicleSettings.Values) {
        binding.capacityInput.setText(number(values.usableCapacityKwhWhenNew))
        binding.healthInput.setText(number(values.stateOfHealthPercent))
        binding.minPowerInput.setText(number(values.minChargerPowerKw))
        binding.reserveInput.setText(number(values.reservePercent))
        binding.capacityLayout.helperText = getString(
            R.string.vehicle_helper_capacity,
            number(VehicleSettings.DEFAULT_CAPACITY_KWH),
        )
        binding.healthLayout.helperText = getString(
            R.string.vehicle_helper_health,
            number(VehicleSettings.DEFAULT_HEALTH_PERCENT),
        )
        binding.minPowerLayout.helperText = getString(
            R.string.vehicle_helper_min_power,
            number(VehicleSettings.DEFAULT_MIN_POWER_KW),
        )
        binding.reserveLayout.helperText = getString(
            R.string.vehicle_helper_reserve,
            number(VehicleSettings.DEFAULT_RESERVE_PERCENT),
        )
    }

    private fun announce(text: String) {
        message = text
        render()
    }

    private fun render() {
        binding.root.removeCallbacks(gateExpiry)
        val nowMs = System.currentTimeMillis()
        val gate = ParkedDeletionPolicy.gate(speedKmh, speedObservedAtMs, nowMs)
        if (gate == ParkedDeletionGate.PARKED) {
            val ageMs = nowMs - checkNotNull(speedObservedAtMs)
            binding.root.postDelayed(
                gateExpiry,
                ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L,
            )
        }
        val editable = gate == ParkedDeletionGate.PARKED
        binding.capacityLayout.isEnabled = editable
        binding.healthLayout.isEnabled = editable
        binding.minPowerLayout.isEnabled = editable
        binding.reserveLayout.isEnabled = editable
        binding.vehicleSaveAction.isEnabled = editable
        binding.vehicleResetAction.isEnabled = editable

        binding.vehicleStatus.text = message ?: when (gate) {
            ParkedDeletionGate.MOVING -> getString(R.string.routing_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE -> getString(R.string.routing_speed_unavailable)
            ParkedDeletionGate.PARKED -> {
                val values = VehicleSettings.read(this)
                val inForce = getString(
                    R.string.vehicle_status,
                    number(values.usableCapacityKwhWhenNew),
                    number(values.stateOfHealthPercent),
                    number(values.minChargerPowerKw),
                    number(values.reservePercent),
                )
                val whose = getString(
                    if (values.isDefault) {
                        R.string.vehicle_status_default
                    } else {
                        R.string.vehicle_status_custom
                    }
                )
                "$inForce $whose"
            }
        }
        message = null
    }

    /** Trailing zeroes off: "100" reads as a figure, "100,0" reads as a measurement. */
    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }

    private val gateExpiry = Runnable { render() }
}
