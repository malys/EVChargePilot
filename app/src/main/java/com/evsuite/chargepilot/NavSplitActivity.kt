package com.evsuite.chargepilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.IBinder
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.evsuite.chargepilot.databinding.ActivityNavSplitBinding

/**
 * CP-094, emulator only: the companion quarter as [MapCompanionOverlay] draws it over the car's
 * map, with a placeholder where the map would be (`mise run split-preview`). The emulator has no
 * MG4 Navigator, and the window-bounds check measures the quarter here.
 *
 * Debug builds with [EXTRA_PREVIEW] only; anything else finishes at once. On the car the
 * companion is never an activity: an activity on top pauses the map (Android 9 resumes one).
 */
class NavSplitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavSplitBinding
    private lateinit var split: SplitCompanion
    private var recorder: TripRecordingService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TripRecordingService.LocalBinder)?.service ?: return
            recorder = bound
            bound.setListener(this@NavSplitActivity, split::render)
            bound.latest?.let(split::render)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The night palette, as the overlay draws it beside the car's dark map.
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        val preview = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (!preview) return finish()
        binding = ActivityNavSplitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapWaiting.setText(R.string.nav_split_preview_map)
        binding.splitFullMap.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) { finish() }
        split = SplitCompanion(this, binding, intent, preview = true) { recorder }
    }

    override fun onStart() {
        super.onStart()
        if (::split.isInitialized) {
            bindService(Intent(this, TripRecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (::split.isInitialized) {
            recorder?.clearListener(this)
            recorder = null
            unbindService(connection)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::split.isInitialized) split.close()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PREVIEW = "split.preview"
    }
}
