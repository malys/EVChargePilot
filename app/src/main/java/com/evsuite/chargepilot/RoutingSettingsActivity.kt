package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityRoutingSettingsBinding
import com.evsuite.chargepilot.route.RoutingConfig
import com.evsuite.chargepilot.route.RoutingConfigImport
import com.evsuite.chargepilot.route.RoutingCredentials
import java.util.concurrent.Executors

/**
 * Where the driver's own routing key is entered, imported or removed.
 *
 * CP-043 refused to ship a key inside the APK: a published APK is a zip, and a key in a zip is
 * not a secret. That decision only works if configuring one is realistic, which on this head
 * unit means a `key = value` file on a USB stick — the same file format EVABRPUploader already
 * uses, so a driver who configured that app knows this one.
 *
 * **The key is never displayed back.** The field starts empty even when a key is stored, the
 * status line says configured or not and never shows a value, and nothing here reaches
 * `AppLogger` or the diagnostic export. A screen that can show a secret is a screen that shows
 * it to a passenger with a phone camera.
 *
 * Parked-only, like every other driver action in this app: entering a key means a keyboard.
 * Clearing one does not, which is why it stays available — a driver who wants their key off the
 * car should not have to stop first.
 */
class RoutingSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoutingSettingsBinding

    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-routing-config")
    }

    private var recorder: TripRecordingService? = null
    private var bound = false
    private var speedKmh: Float? = null
    private var speedObservedAtMs: Long? = null

    /** Set by an action, cleared by the next gate render: the outcome outranks the status. */
    private var message: String? = null

    private val gateExpiry = Runnable { render() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = value
            value.setListener(this@RoutingSettingsActivity) { snapshot ->
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
        binding = ActivityRoutingSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backAction.setOnClickListener { finish() }
        binding.routingSaveAction.setOnClickListener { save() }
        binding.routingImportAction.setOnClickListener { import() }
        binding.routingClearAction.setOnClickListener { clear() }
        binding.routingBaseUrlInput.setText(RoutingCredentials.baseUrl(this))
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
        // The typed keys do not survive the screen going away.
        binding.routingKeyInput.text?.clear()
        binding.chargerKeyInput.text?.clear()
        super.onStop()
    }

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    private fun save() {
        val key = binding.routingKeyInput.text?.toString()?.trim().orEmpty()
        val chargerKey = binding.chargerKeyInput.text?.toString()?.trim().orEmpty()
        if (key.isEmpty() && chargerKey.isEmpty()) {
            announce(getString(R.string.routing_key_missing))
            return
        }
        val typedBaseUrl = binding.routingBaseUrlInput.text?.toString()?.trim().orEmpty()
        val baseUrl = if (typedBaseUrl.isEmpty()) RoutingConfig.DEFAULT_BASE_URL else typedBaseUrl
        RoutingConfig.refuseBaseUrl(baseUrl)?.let { reason ->
            announce(getString(R.string.routing_base_url_refused, reason))
            return
        }
        // Only what was typed: saving a charger key must not wipe a routing key already stored.
        RoutingCredentials.apply(
            this,
            RoutingConfig(
                apiKey = key.ifEmpty { null },
                baseUrl = RoutingConfig.validBaseUrl(baseUrl),
                chargerApiKey = chargerKey.ifEmpty { null },
            ),
        )
        binding.routingKeyInput.text?.clear()
        binding.chargerKeyInput.text?.clear()
        announce(getString(R.string.routing_saved))
    }

    /**
     * The USB stick first and this app's own folders with it, because on this head unit the
     * system picker answers "no apps can perform this action" and there is nothing to fall back
     * to. Off the main thread: a stick can be slow, and a scan that blocks is a frozen car.
     */
    private fun import() {
        val directories = buildList {
            addAll(getExternalFilesDirs(null).filterNotNull())
            addAll(DiagnosticUsbStorage.roots(this@RoutingSettingsActivity))
        }
        disk.execute {
            val found = RoutingConfigImport.search(directories)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (found == null) {
                    announce(getString(R.string.routing_import_none))
                    return@runOnUiThread
                }
                RoutingCredentials.apply(this, found.config)
                binding.routingBaseUrlInput.setText(RoutingCredentials.baseUrl(this))
                // The file name, never its contents: the contents are the key.
                announce(getString(R.string.routing_import_done, found.file.name))
            }
        }
    }

    private fun clear() {
        RoutingCredentials.clear(this)
        binding.routingKeyInput.text?.clear()
        binding.chargerKeyInput.text?.clear()
        binding.routingBaseUrlInput.setText(RoutingConfig.DEFAULT_BASE_URL)
        announce(getString(R.string.routing_cleared))
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
            binding.root.postDelayed(gateExpiry, ParkedDeletionPolicy.MAX_READING_AGE_MS - ageMs + 1L)
        }
        val editable = gate == ParkedDeletionGate.PARKED
        binding.routingKeyLayout.isEnabled = editable
        binding.chargerKeyLayout.isEnabled = editable
        binding.routingBaseUrlLayout.isEnabled = editable
        binding.routingSaveAction.isEnabled = editable
        binding.routingImportAction.isEnabled = editable

        binding.routingStatus.text = message ?: when (gate) {
            ParkedDeletionGate.MOVING -> getString(R.string.routing_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE -> getString(R.string.routing_speed_unavailable)
            ParkedDeletionGate.PARKED -> {
                val routing = if (RoutingCredentials.isConfigured(this)) {
                    getString(R.string.routing_configured, RoutingCredentials.baseUrl(this))
                } else {
                    getString(R.string.routing_absent)
                }
                val chargers = if (RoutingCredentials.isChargerConfigured(this)) {
                    getString(R.string.routing_charger_configured)
                } else {
                    getString(R.string.routing_charger_absent)
                }
                "$routing $chargers"
            }
        }
        message = null
    }
}
