package com.evsuite.chargepilot

import android.app.Application
import com.evsuite.hardware.diag.CrashLogger

class ChargePilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this, "EVChargePilot")
    }
}

