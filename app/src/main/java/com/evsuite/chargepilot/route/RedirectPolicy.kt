package com.evsuite.chargepilot.route

import java.net.URI

/**
 * Which hops a routing request is allowed to follow.
 *
 * The workspace `AGENTS.md` states this rule for updaters, and it is not an updater rule: an
 * `https` → `http` `Location` header is a man in the middle telling the client to stop
 * encrypting, and a client that follows redirects blindly does what it is told. This request
 * carries the driver's position, so the answer is the same as for an APK download.
 *
 * The allowed host is the configured base URL's host and nothing else. Not a suffix match —
 * `openrouteservice.org.attacker.example` ends with the right string — and not a list that grows
 * quietly. One host, the one the driver configured, which is also what makes a self-hosted
 * instance work without a code change.
 */
object RedirectPolicy {

    /** Enough for a load balancer, not enough to be walked somewhere. */
    const val MAX_HOPS = 3

    sealed interface Outcome {
        data class Follow(val url: String) : Outcome
        data class Refuse(val reason: String) : Outcome
    }

    /**
     * @param location the raw `Location` header, which may be relative.
     * @param from the URL that produced it, for resolving a relative location.
     * @param allowedHost the configured base URL's host.
     * @param hop how many redirects have already been followed.
     */
    fun evaluate(location: String?, from: String, allowedHost: String, hop: Int): Outcome {
        if (location.isNullOrBlank()) return Outcome.Refuse("redirect without a location")
        if (hop >= MAX_HOPS) return Outcome.Refuse("too many redirects")
        val resolved = runCatching { URI(from).resolve(location.trim()) }.getOrNull()
            ?: return Outcome.Refuse("redirect to an unparseable location")
        if (!resolved.scheme.equals("https", ignoreCase = true)) {
            return Outcome.Refuse("redirect leaves https")
        }
        val host = resolved.host ?: return Outcome.Refuse("redirect without a host")
        if (!host.equals(allowedHost, ignoreCase = true)) {
            return Outcome.Refuse("redirect to another host")
        }
        return Outcome.Follow(resolved.toString())
    }

    /** The first URL is held to the same rule, so nothing reaches the socket unchecked. */
    fun refuseInitial(url: String, allowedHost: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "not a URL"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "not https"
        val host = uri.host ?: return "no host"
        if (!host.equals(allowedHost, ignoreCase = true)) return "not the configured host"
        if (uri.userInfo != null) return "credentials in the URL"
        return null
    }
}
