package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.evsuite.chargepilot.databinding.ActivitySettingsBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * Where every setting of this app is reached from.
 *
 * The vehicle figures and the routing key used to be two buttons in the charge planner's top
 * bar, which is where a driver looked for them only if they had already been told. They are
 * configuration, not planning, so they live on the configuration page and the planner keeps its
 * bar for the plan. Each subject keeps its own sheet: this page indexes them, it does not
 * duplicate their fields.
 *
 * The USB transfer is the exception, and it belongs here rather than in a sheet. The file carries
 * keys, addresses, saved destinations and the car's own figures — everything the app is
 * configured with — so it was never the routing key's own action, and it was offered from the
 * routing key screen and the destination screen at once, two places for one file. It is the whole
 * application's import and export, so it sits on the page that holds the whole application's
 * settings, once.
 *
 * Parked only, like every other driver action here: browsing a stick is a list to read and a file
 * to recognise, and an import rewrites the configuration under the driver's hands.
 */
class SettingsActivity : PrimaryNavigationActivity() {
    override val primaryPage = PrimaryPage.SETTINGS

    private lateinit var binding: ActivitySettingsBinding

    private val disk = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-settings-transfer")
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
            value.setListener(this@SettingsActivity) { snapshot ->
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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.vehicleSettingsAction.setOnClickListener {
            startActivity(Intent(this, VehicleSettingsActivity::class.java))
        }
        binding.routingSettingsAction.setOnClickListener {
            startActivity(Intent(this, RoutingSettingsActivity::class.java))
        }
        binding.settingsImportAction.setOnClickListener { import() }
        binding.settingsExportAction.setOnClickListener { export() }
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

    override fun onDestroy() {
        disk.shutdownNow()
        super.onDestroy()
    }

    /**
     * The driver browses the stick and taps the file, because on this head unit the system picker
     * answers "no apps can perform this action" and there is nothing to fall back to. Volume
     * discovery is off the main thread: a stick can be slow, and a scan that blocks is a frozen
     * car. The browser then starts at the volume root, so a file put anywhere on the stick is
     * reachable instead of only the folders a scan happened to look in.
     */
    private fun import() {
        disk.execute {
            val roots = DiagnosticUsbStorage.roots(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    announce(getString(R.string.settings_import_none))
                } else {
                    StorageBrowserDialog.pickFile(this, roots, R.string.settings_import_pick) {
                        importFile(it)
                    }
                }
            }
        }
    }

    private fun importFile(file: File) {
        disk.execute {
            val settings = SettingsTransfer.read(file)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (settings.isEmpty()) {
                    // The path they chose, so "this one is not it" is the answer, not silence.
                    announce(getString(R.string.settings_import_unusable, file.name))
                    return@runOnUiThread
                }
                // Keys, saved destinations and the car's own figures ride in the same file: a
                // driver setting up a second car imports once and has the app, not half of it.
                SettingsTransfer.apply(this, settings)
                // The file name, never its contents: the contents are the keys.
                announce(getString(R.string.settings_import_done, file.name))
            }
        }
    }

    /**
     * The same file back out, so a second car — or the unstable channel, which is a separate
     * application id with its own preferences — does not mean typing the key again.
     *
     * A removable volume only: writing the keys into this app's private folder would be a second
     * unencrypted copy nobody asked for and nobody could reach. The stick then carries the keys
     * in clear text, which is what the announcement says. The driver browses to the folder, same
     * as the diagnostic export's, so a car with more than one volume mounted does not have the
     * driver's stick guessed at and the file lands where they will look for it.
     */
    private fun export() {
        val settings = SettingsTransfer.snapshot(this)
        if (settings.isEmpty()) {
            announce(getString(R.string.settings_export_empty))
            return
        }
        disk.execute {
            val roots = DiagnosticUsbStorage.roots(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (roots.isEmpty()) {
                    announce(getString(R.string.settings_export_failed))
                } else {
                    StorageBrowserDialog.pickFolder(this, roots, R.string.settings_export_pick) {
                        writeExport(it, settings)
                    }
                }
            }
        }
    }

    private fun writeExport(directory: File, settings: SettingsTransfer.Settings) {
        disk.execute {
            val written = DiagnosticUsbStorage.writableTarget(this, directory)
                ?.let { SettingsTransfer.write(it, settings) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (written == null) {
                    announce(getString(R.string.settings_export_failed))
                } else {
                    announce(getString(R.string.settings_export_done, written.name))
                }
            }
        }
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
        val parked = gate == ParkedDeletionGate.PARKED
        binding.settingsImportAction.isEnabled = parked
        binding.settingsExportAction.isEnabled = parked

        binding.settingsTransferStatus.text = message ?: when (gate) {
            ParkedDeletionGate.MOVING -> getString(R.string.routing_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE -> getString(R.string.routing_speed_unavailable)
            ParkedDeletionGate.PARKED -> getString(R.string.settings_transfer_ready)
        }
    }
}
