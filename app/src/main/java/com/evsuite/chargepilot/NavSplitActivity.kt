package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNavGuidance
import com.evsuite.hardware.telemetry.EnergySnapshot
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
 * The guidance is read through the synchronous getters, like the arrival screen reads it. This
 * screen never registers with the adapter's listener.
 */
class NavSplitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavSplitBinding
    private lateinit var pane: MapPane
    private lateinit var drift: DriftCompanion

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-split")
    }

    private var recorder: TripRecordingService? = null
    private var handedBack = false
    private var guidanceReadAtMs = 0L

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
        drift = DriftCompanion(this)
        worker.execute { runCatching { drift.load() } }
        binding.splitFullMap.setOnClickListener { handBack() }
        // Back hands the map back, as the button does.
        onBackPressedDispatcher.addCallback(this) { handBack() }

        pane = MapPane(this)
        val map = MapApps.comeForward(this)
        val view = pane.view
        if (map == null || view == null) {
            handBack()
            return
        }
        binding.mapPane.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        pane.start(map) { handBack() }
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

    private fun render(value: EnergySnapshot) {
        val unavailable = getString(R.string.value_unavailable)
        binding.splitSoc.text =
            value.socPercent?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: unavailable
        binding.splitRange.text =
            value.rangeKm?.let { String.format(Locale.getDefault(), "%.0f km", it) } ?: unavailable
        val line = drift.line(value, value.timestampMs)
        binding.splitDrift.visibility = if (line == null) View.GONE else View.VISIBLE
        binding.splitDrift.text = line
        // Several binder reads per refresh: every few seconds is enough for a remaining distance.
        if (value.timestampMs - guidanceReadAtMs < GUIDANCE_REFRESH_MS) return
        guidanceReadAtMs = value.timestampMs
        worker.execute {
            val guidance = runCatching { SaicNavGuidance.readNow() }.getOrNull()
            val km = guidance?.remainingDistanceKm(FirmwareInfo.getGeneration())
            val text = if (guidance == null || (km == null && guidance.road == null)) null else getString(
                R.string.arrival_route,
                km?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: unavailable,
                guidance.remainingMinutes?.toString() ?: unavailable,
                guidance.road.orEmpty(),
            )
            runOnUiThread {
                if (!isDestroyed) binding.splitRoute.text = text
            }
        }
    }

    private companion object {
        const val GUIDANCE_REFRESH_MS = 5_000L
    }
}
