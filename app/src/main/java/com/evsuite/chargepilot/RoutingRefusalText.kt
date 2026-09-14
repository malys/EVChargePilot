package com.evsuite.chargepilot

import android.content.Context
import com.evsuite.chargepilot.route.RoutingTransport

/**
 * A refusal the driver can act on. The detail travels only where it means something to them —
 * seconds to wait, a status code — never the transport's own English reason string.
 *
 * One mapping, because there was two: the destination screen and the charging-stop screen each
 * carried a private copy, word for word, and a reason added to [RoutingTransport.Reason] had to
 * be spelled twice or one screen would fail to compile — which is the good case. The bad one is
 * the two drifting apart and a driver being told different things about the same refusal.
 */
internal fun Context.routingRefusal(result: RoutingTransport.Result.Refused): String =
    when (result.reason) {
        RoutingTransport.Reason.NOT_CONFIGURED -> getString(R.string.charge_stop_not_configured)
        RoutingTransport.Reason.BUSY -> getString(R.string.routing_refused_busy)
        RoutingTransport.Reason.QUOTA_MINUTE ->
            getString(R.string.routing_refused_quota_minute, result.detail.orEmpty())
        RoutingTransport.Reason.QUOTA_DAY -> getString(R.string.routing_refused_quota_day)
        RoutingTransport.Reason.TRANSPORT -> getString(R.string.routing_refused_transport)
        RoutingTransport.Reason.SERVER_DAILY_LIMIT -> getString(R.string.routing_refused_server_day)
        RoutingTransport.Reason.SERVER_KEY_REFUSED -> getString(R.string.routing_refused_server_key)
        RoutingTransport.Reason.SERVER_RATE_LIMIT ->
            getString(R.string.routing_refused_server_minute)
        RoutingTransport.Reason.SERVER_REJECTED ->
            getString(R.string.routing_refused_server, result.detail.orEmpty())
        RoutingTransport.Reason.UNREADABLE -> getString(R.string.routing_refused_unreadable)
    }
