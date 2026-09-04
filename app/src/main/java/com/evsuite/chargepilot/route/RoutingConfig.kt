package com.evsuite.chargepilot.route

import java.net.URI
import java.util.Locale

/**
 * The routing key and base URL, and the plain text file they can arrive in.
 *
 * CP-043 chose a key the driver enters rather than one shipped in the APK, because an APK
 * published on GitHub Releases is a zip and a key inside it is not a secret. That only works if
 * entering it is possible: this head unit's on-screen keyboard and a 60-character key are not a
 * combination anyone survives twice. EVABRPUploader answered the same question with a
 * `key = value` file dropped on a USB stick, and this is the same format so a driver who has
 * configured one app already knows the other.
 *
 * ```
 * # EVChargePilot routing
 * ors_api_key  = 5b3ce35...
 * ors_base_url = https://api.heigit.org
 * ocm_api_key  = 0a1b2c3...
 * ```
 *
 * Two keys, two services, two sign-ups: the second one names where the charging stop is
 * (CP-048) and is optional. Without it the app still says *stop in N kilometres*; it just
 * cannot say where.
 *
 * Every field is nullable: the caller applies what the file set and leaves the rest alone, so a
 * file carrying only a key does not silently reset a self-hosted base URL.
 */
data class RoutingConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val chargerApiKey: String? = null,
    val chargerBaseUrl: String? = null,
) {

    /** Nothing usable in it — an empty file, or a file that was never a config. */
    fun isEmpty(): Boolean =
        apiKey == null && baseUrl == null && chargerApiKey == null && chargerBaseUrl == null

    companion object {

        /** Big enough for any config, small enough that a wrong file is refused before it is read. */
        const val MAX_FILE_BYTES = 64 * 1024

        /**
         * HeiGIT's host, not the old `api.openrouteservice.org`. That one was deprecated on
         * 2026-04-28, cut to 10 % of the published quota on 2026-08-27 and is scheduled to be
         * switched off on 2026-09-28. Every HeiGIT API now has the shape
         * `api.heigit.org/<service>/<version>/`, which is why the two paths in this package no
         * longer look alike: directions is openrouteservice, geocoding is Pelias.
         */
        val DEFAULT_BASE_URL = "https://api.heigit.org"

        fun parse(text: String): RoutingConfig {
            var apiKey: String? = null
            var baseUrl: String? = null
            var chargerApiKey: String? = null
            var chargerBaseUrl: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val key = line.substring(0, separator).trim().lowercase(Locale.US)
                val value = line.substring(separator + 1).trim()
                if (value.isEmpty()) continue
                when (key) {
                    "ors_api_key", "api_key", "apikey" -> apiKey = value
                    "ors_base_url", "base_url", "url" -> baseUrl = validBaseUrl(value)
                    "ocm_api_key", "charger_api_key" -> chargerApiKey = value
                    "ocm_base_url", "charger_base_url" ->
                        chargerBaseUrl = validBaseUrl(value)
                    // Unknown keys are ignored rather than rejected: one file can configure
                    // several apps, and a future key must not break an older build.
                }
            }
            return RoutingConfig(apiKey, baseUrl, chargerApiKey, chargerBaseUrl)
        }

        /**
         * The base URL, or null with a reason.
         *
         * This is the only place a host is accepted, so it is where the transport's trust starts.
         * `https` because CP-043 says so and because a route request carries the driver's
         * position. No userinfo, because credentials in a URL end up in logs, in redirects and
         * in `Referer` headers. No query and no fragment, because a base URL that already
         * carries parameters cannot have any appended safely.
         */
        fun refuseBaseUrl(value: String): String? {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
                ?: return "not a URL"
            if (!uri.isAbsolute) return "not an absolute URL"
            if (!uri.scheme.equals("https", ignoreCase = true)) return "must be https"
            if (uri.host.isNullOrBlank()) return "no host"
            if (uri.userInfo != null) return "must not carry credentials"
            if (uri.query != null || uri.fragment != null) return "must not carry a query"
            return null
        }

        fun validBaseUrl(value: String): String? =
            if (refuseBaseUrl(value) == null) value.trim().trimEnd('/') else null
    }
}
