package com.evsuite.chargepilot

import android.app.Application
import android.content.pm.ApplicationInfo
import com.evsuite.hardware.diag.CrashLogger
import java.io.File

class ChargePilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this, "EVChargePilot")
        // Unstable arms the validation probes here so a drive is recorded without anyone
        // opening a screen first; stable does nothing, and contains none of this.
        EvidenceCaptureHook.startProbes(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) seedSettings()
    }

    /**
     * Applies settings dropped into this build's own files directory, once, then renames them.
     *
     * Debug builds only: it is how the emulator gets the driver's keys, favourites and vehicle
     * figures without a key typed on a touch keyboard or a USB stick carried back from the car.
     * `mise run seed-settings` puts the file there. A release build has no such path — the only
     * way in stays the stick the driver browsed to.
     */
    private fun seedSettings() {
        val seed = File(filesDir, SEED_FILE)
        if (!seed.isFile) return
        val settings = SettingsTransfer.read(seed)
        if (!settings.isEmpty()) SettingsTransfer.apply(this, settings)
        seed.renameTo(File(filesDir, "$SEED_FILE.applied"))
    }

    private companion object {
        const val SEED_FILE = "seed-settings.json"
    }
}

