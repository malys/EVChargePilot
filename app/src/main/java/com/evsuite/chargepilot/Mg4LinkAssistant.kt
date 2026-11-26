package com.evsuite.chargepilot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.evsuite.hardware.AppLogger
import java.util.Locale

/**
 * Hands a destination to the phone over Android Auto (MG4Link#71, 2.2.8-beta5). Android Auto
 * takes no place from the head unit, so MG4Link opens the phone's assistant and speaks
 * "Navigate to …" into it, unheard in the car. An explicit broadcast behind MG4Link's signature
 * permission; an older MG4Link has no receiver and drops it.
 *
 * Used only when the driver chose the Android Auto split: MG4 Navigator's offline handoff is a
 * separate path this never touches.
 */
internal object Mg4LinkAssistant {

    const val ACTION = "${MapApps.MG4LINK_PACKAGE}.ASSISTANT_COMMAND"
    const val PERMISSION = "${MapApps.MG4LINK_PACKAGE}.permission.ASSISTANT_COMMAND"

    /** Coordinates always, the label when there is one; the assistant's answer is not played. */
    fun request(latitude: Double, longitude: Double, label: String?, language: String): Intent =
        Intent(ACTION)
            .setPackage(MapApps.MG4LINK_PACKAGE)
            .putExtra("lat", latitude)
            .putExtra("lon", longitude)
            .apply { label?.takeIf { it.isNotBlank() }?.let { putExtra("label", it) } }
            .putExtra("lang", language)
            .putExtra("speakReply", false)

    /** False when the permission is missing: MG4Link 2.2.8-beta5 must be installed before this app. */
    fun navigate(context: Context, latitude: Double, longitude: Double, label: String?): Boolean {
        val language = Locale.getDefault().language
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.i(TAG, "assistant command not sent: permission missing (MG4Link 2.2.8-beta5 installed after this app?)")
            return false
        }
        // No place in the log: a destination is personal data, the bundle keeps only the outcome.
        return runCatching { context.sendBroadcast(request(latitude, longitude, label, language)) }
            .onSuccess { AppLogger.i(TAG, "assistant command sent: lang=$language label=${!label.isNullOrBlank()}") }
            .onFailure { AppLogger.w(TAG, "assistant command failed: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    private const val TAG = "CP_MG4LINK"
}
