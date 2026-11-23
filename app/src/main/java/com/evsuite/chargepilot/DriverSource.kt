package com.evsuite.chargepilot

import android.content.Context
import android.net.Uri
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.DriverContract

/**
 * CP-098: the driver EVTasker published for this ignition cycle, or null.
 *
 * EVTasker absent, its provider missing, the permission refused, no driver set: all of them are
 * null, never an error the dashboard has to handle. Logged once per process, and never with the
 * name — it is personal data.
 */
object DriverSource {

    data class Published(val name: String, val setAtMs: Long)

    @Volatile
    private var unavailableLogged = false

    /** Blocking IPC: call off the main thread. */
    fun current(context: Context): Published? = runCatching {
        context.contentResolver.query(Uri.parse(DriverContract.URI_CURRENT), null, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = DriverContract.normalizeName(
                    cursor.getString(cursor.getColumnIndexOrThrow(DriverContract.COLUMN_NAME)),
                ) ?: return@use null
                Published(name, cursor.getLong(cursor.getColumnIndexOrThrow(DriverContract.COLUMN_SET_AT_MS)))
            }
    }.onFailure {
        if (!unavailableLogged) {
            unavailableLogged = true
            AppLogger.i(TAG, "driver source unavailable: ${it.javaClass.simpleName}")
        }
    }.getOrNull()

    /** The published driver when it was set for the trip [startMs]..[endMs]. */
    fun forTrip(context: Context, startMs: Long, endMs: Long): String? =
        current(context)?.takeIf { DriverContract.belongsToTrip(it.setAtMs, startMs, endMs) }?.name

    private const val TAG = "CP_DRIVERS"
}
