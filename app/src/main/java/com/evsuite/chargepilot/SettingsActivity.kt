package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.evsuite.chargepilot.databinding.ActivitySettingsBinding
import com.evsuite.chargepilot.route.RoutingConfig
import com.evsuite.chargepilot.route.RoutingCredentials
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Everything this app is configured with, on the page itself.
 *
 * It used to be an index: a card per subject, each with a button that opened its own sheet. That
 * was reported from the car on 2026-09-10 as two windows for one subject, and it was right —
 * an index of two entries is a menu the driver reads before it tells them anything. The vehicle
 * figures and the keys are now sections on this page, and one Save at the bottom applies both.
 *
 * The USB transfer is in the top bar rather than in a card. The file carries keys, addresses,
 * saved destinations and the car's own figures at once, so it belongs to no single section — and
 * a card holding two buttons and a paragraph read as a fourth subject on a page that has three.
 *
 * Two columns, and four actions on the whole page: import, export, Save, and one Remove. It was
 * reported from the car on 2026-09-10 as too long and too many buttons for a vehicle panel, and
 * the Reset beside the Remove was two words for the same idea. A vehicle field left at its
 * default is now simply empty, which is what `VehicleSettings.parse` has always read a cleared
 * box as, so clearing the boxes and saving *is* the reset and the button for it is gone. The
 * keys keep theirs: a stored key is never shown, so an empty box cannot mean "remove it".
 *
 * Parked only, like every typing screen in this app: four numeric fields and three text ones is a
 * keyboard, and a keyboard at 130 km/h is not a setting, it is a hazard. Browsing a stick is a
 * list to read, and an import rewrites the configuration under the driver's hands.
 *
 * **A stored key is never displayed back.** The key fields start empty even when a key is stored,
 * the routing line says configured or not and never shows a value, and both fields are cleared
 * when the page goes away. A screen that can show a secret is a screen that shows it to a
 * passenger with a phone camera.
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
        binding.settingsSaveAction.setOnClickListener { save() }
        binding.routingClearAction.setOnClickListener { clearKeys() }
        binding.settingsImportAction.setOnClickListener { import() }
        binding.settingsExportAction.setOnClickListener { export() }
        fill(VehicleSettings.read(this))
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

    /**
     * One Save for the page: the vehicle figures, then the keys.
     *
     * The figures are parsed first because they are the only part that can be refused on its own
     * content, and a refusal must not leave half the page written. Empty key fields are no longer
     * an error: on a merged page the driver saving a changed reserve has not asked to be told
     * about a key they never typed.
     */
    private fun save() {
        val parsed = VehicleSettings.parse(
            binding.capacityInput.text?.toString().orEmpty(),
            binding.healthInput.text?.toString().orEmpty(),
            binding.minPowerInput.text?.toString().orEmpty(),
            binding.reserveInput.text?.toString().orEmpty(),
        )
        if (parsed is VehicleSettings.Parsed.Refused) {
            announce(
                getString(
                    when (parsed.field) {
                        VehicleSettings.Field.CAPACITY -> R.string.vehicle_refused_capacity
                        VehicleSettings.Field.HEALTH -> R.string.vehicle_refused_health
                        VehicleSettings.Field.MIN_POWER -> R.string.vehicle_refused_min_power
                        VehicleSettings.Field.RESERVE -> R.string.vehicle_refused_reserve
                    }
                )
            )
            return
        }
        val values = (parsed as VehicleSettings.Parsed.Ok).values
        // Four empty boxes is the reset. `parse` has always read a cleared box as the documented
        // default, so clearing the preferences rather than writing the defaults into them keeps
        // "the driver never set this" distinguishable from "the driver typed the default".
        if (values.isDefault) VehicleSettings.clear(this) else VehicleSettings.write(this, values)
        fill(values)

        val key = binding.routingKeyInput.text?.toString()?.trim().orEmpty()
        val chargerKey = binding.chargerKeyInput.text?.toString()?.trim().orEmpty()
        val typedBaseUrl = binding.routingBaseUrlInput.text?.toString()?.trim().orEmpty()
        val baseUrl = if (typedBaseUrl.isEmpty()) RoutingConfig.DEFAULT_BASE_URL else typedBaseUrl
        RoutingConfig.refuseBaseUrl(baseUrl)?.let { reason ->
            // The figures are already written, so the message names what was refused, not the page.
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
        announce(
            if (key.isEmpty() && chargerKey.isEmpty()) {
                getString(R.string.vehicle_saved)
            } else {
                getString(R.string.routing_saved)
            }
        )
    }

    private fun clearKeys() {
        RoutingCredentials.clear(this)
        binding.routingKeyInput.text?.clear()
        binding.chargerKeyInput.text?.clear()
        binding.routingBaseUrlInput.setText(RoutingConfig.DEFAULT_BASE_URL)
        announce(getString(R.string.routing_cleared))
    }

    /**
     * The four boxes, holding what the driver overrode and nothing else.
     *
     * A field left at its documented default starts empty, and its helper names that default.
     * That is `VehicleSettings.parse`'s own rule — a cleared box has always meant the default —
     * made visible, and it is why this page no longer carries a Reset button beside a Remove
     * button: clearing the boxes and saving is the reset.
     */
    private fun fill(values: VehicleSettings.Values) {
        set(
            binding.capacityLayout, binding.capacityInput,
            values.usableCapacityKwhWhenNew, VehicleSettings.DEFAULT_CAPACITY_KWH,
            R.string.vehicle_helper_capacity,
        )
        set(
            binding.healthLayout, binding.healthInput,
            values.stateOfHealthPercent, VehicleSettings.DEFAULT_HEALTH_PERCENT,
            R.string.vehicle_helper_health,
        )
        set(
            binding.minPowerLayout, binding.minPowerInput,
            values.minChargerPowerKw, VehicleSettings.DEFAULT_MIN_POWER_KW,
            R.string.vehicle_helper_min_power,
        )
        set(
            binding.reserveLayout, binding.reserveInput,
            values.reservePercent, VehicleSettings.DEFAULT_RESERVE_PERCENT,
            R.string.vehicle_helper_reserve,
        )
        binding.vehicleWhose.text = getString(
            if (values.isDefault) R.string.vehicle_status_default else R.string.vehicle_status_custom
        )
    }

    private fun set(
        layout: TextInputLayout,
        input: TextInputEditText,
        value: Double,
        default: Double,
        helper: Int,
    ) {
        input.setText(if (value == default) "" else number(value))
        layout.helperText = getString(helper, number(default))
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
                // The fields on this page are now behind what was just written.
                fill(VehicleSettings.read(this))
                binding.routingBaseUrlInput.setText(RoutingCredentials.baseUrl(this))
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
        binding.capacityLayout.isEnabled = parked
        binding.healthLayout.isEnabled = parked
        binding.minPowerLayout.isEnabled = parked
        binding.reserveLayout.isEnabled = parked
        binding.routingKeyLayout.isEnabled = parked
        binding.chargerKeyLayout.isEnabled = parked
        binding.routingBaseUrlLayout.isEnabled = parked
        binding.settingsSaveAction.isEnabled = parked
        binding.settingsImportAction.isEnabled = parked
        binding.settingsExportAction.isEnabled = parked

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
        binding.routingState.text = "$routing $chargers"

        // One line for the page. Three sheets meant three of these, which said "the vehicle is
        // moving" three times over for one car.
        binding.settingsStatus.text = message ?: when (gate) {
            ParkedDeletionGate.MOVING -> getString(R.string.routing_moving)
            ParkedDeletionGate.SPEED_UNAVAILABLE -> getString(R.string.routing_speed_unavailable)
            ParkedDeletionGate.PARKED -> getString(R.string.settings_transfer_ready)
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
}
