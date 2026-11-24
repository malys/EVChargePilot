package com.evsuite.chargepilot

import android.content.Context
import android.content.Intent

/**
 * Asks MG4Link to lay Android Auto out beside the companion instead of under it (MG4Link#69,
 * 2.2.8-beta2). An explicit broadcast behind MG4Link's signature permission; an older MG4Link
 * has no receiver and drops it, and the companion then covers the map as before.
 *
 * MG4Link keeps the request across its Android Auto sessions and forgets it when it restarts,
 * so it is sent each time the window is shown and withdrawn each time it goes.
 */
internal object Mg4LinkMargin {

    const val ACTION_RESERVE_RIGHT = "${MapApps.MG4LINK_PACKAGE}.RESERVE_RIGHT"
    const val EXTRA_PX = "px"
    const val EXTRA_PACKAGE = "package"
    const val PERMISSION = "${MapApps.MG4LINK_PACKAGE}.permission.RESERVE_MARGIN"

    /** The right strip the window covers, in screen pixels; 0 withdraws it. */
    fun request(context: Context, px: Int): Intent =
        Intent(ACTION_RESERVE_RIGHT)
            .setPackage(MapApps.MG4LINK_PACKAGE)
            .putExtra(EXTRA_PX, px.coerceAtLeast(0))
            // Two apps each reserving a strip must not cancel each other's.
            .putExtra(EXTRA_PACKAGE, context.packageName)

    fun reserve(context: Context, px: Int) {
        runCatching { context.sendBroadcast(request(context, px)) }
    }

    fun release(context: Context) = reserve(context, 0)
}
