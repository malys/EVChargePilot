package com.evsuite.chargepilot

import android.app.Application
import com.evsuite.hardware.diag.CrashLogger

class ChargePilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this, "EVChargePilot")
        // Unstable arms the validation probes here so a drive is recorded without anyone
        // opening a screen first; stable does nothing, and contains none of this.
        EvidenceCaptureHook.startProbes(this)
    }
}

