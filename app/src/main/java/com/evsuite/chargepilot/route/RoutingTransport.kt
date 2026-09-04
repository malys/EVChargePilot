package com.evsuite.chargepilot.route

import com.evsuite.hardware.AppLogger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only place this application opens a socket.
 *
 * There is one of these and there must stay one: EVChargePilot's safety argument used to be
 * that it had no network capability at all, and what replaces an argument you get for free is
 * an argument somebody has to maintain. A second HTTP entry point is a second thing to audit,
 * and the audit is the point.
 *
 * What this enforces, all of it from CP-045:
 *
 * - `https` on the initial URL and on every redirect hop, checked by [RedirectPolicy] against
 *   the host the driver configured. Redirects are not followed by `HttpURLConnection` here —
 *   `instanceFollowRedirects` is off — because the platform would follow them without asking.
 * - The key travels in an `Authorization` header. A key in a query string reaches access logs,
 *   `Referer` headers and the next redirect.
 * - Bounded: timeouts, a response cap, and a refusal to read past it. A head unit stuck on a
 *   socket is a head unit that stopped showing speed.
 * - Single flight. A second request while one is in flight is dropped, not queued: the driver
 *   wants the current answer, and a queue is how a screen ends up spending a day's quota.
 *
 * Nothing here logs a key, a URL carrying one, or a response body.
 */
class RoutingTransport(private val quota: RoutingQuota = RoutingQuota()) {

    private val inFlight = AtomicBoolean(false)

    sealed interface Result {
        data class Ok(val body: String) : Result

        /** Refused before or after the socket, with a reason a screen can show. */
        data class Refused(val reason: Reason, val detail: String? = null) : Result
    }

    enum class Reason {
        NOT_CONFIGURED,
        BUSY,
        QUOTA_MINUTE,
        QUOTA_DAY,
        TRANSPORT,
        SERVER_DAILY_LIMIT,
        SERVER_RATE_LIMIT,
        SERVER_REJECTED,
        UNREADABLE,
    }

    /**
     * One POST, one answer. Blocking: callers are already off the main thread and a second
     * thread here would only hide that from them.
     */
    fun post(
        credentials: RoutingCredentials.Values?,
        path: String,
        body: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result = request(credentials, "POST", path, body, nowMs)

    /**
     * One GET, one answer. The query carries what is being searched for and never the key: the
     * key stays in the `Authorization` header, which is the rule for every request this app
     * makes. An endpoint that only accepts a key in its query string is an endpoint this app
     * does without.
     */
    fun get(
        credentials: RoutingCredentials.Values?,
        path: String,
        query: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Result = request(credentials, "GET", path + encodeQuery(query), null, nowMs)

    private fun request(
        credentials: RoutingCredentials.Values?,
        method: String,
        path: String,
        body: String?,
        nowMs: Long,
    ): Result {
        if (credentials == null || credentials.apiKey.isBlank()) {
            return Result.Refused(Reason.NOT_CONFIGURED)
        }
        val allowedHost = runCatching { URI(credentials.baseUrl).host }.getOrNull()
            ?: return Result.Refused(Reason.NOT_CONFIGURED, "base URL has no host")
        val url = credentials.baseUrl.trimEnd('/') + path
        RedirectPolicy.refuseInitial(url, allowedHost)?.let {
            return Result.Refused(Reason.TRANSPORT, it)
        }
        when (val verdict = quota.check(nowMs)) {
            is RoutingQuota.Verdict.Wait -> return Result.Refused(
                if (verdict.window == RoutingQuota.Window.MINUTE) Reason.QUOTA_MINUTE
                else Reason.QUOTA_DAY,
                verdict.seconds.toString(),
            )
            RoutingQuota.Verdict.Allowed -> Unit
        }
        if (!inFlight.compareAndSet(false, true)) return Result.Refused(Reason.BUSY)
        return try {
            quota.record(nowMs)
            send(url, allowedHost, credentials.apiKey, method, body, hop = 0)
        } finally {
            inFlight.set(false)
        }
    }

    private fun send(
        url: String,
        allowedHost: String,
        apiKey: String,
        method: String,
        body: String?,
        hop: Int,
    ): Result {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrNull() ?: return Result.Refused(Reason.TRANSPORT, "cannot open")
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", apiKey)
            connection.setRequestProperty("Accept", "application/geo+json")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val status = connection.responseCode
            quota.observe(connection.getHeaderField(HEADER_REMAINING)?.toIntOrNull())
            when {
                status in 300..399 -> {
                    val outcome = RedirectPolicy.evaluate(
                        connection.getHeaderField("Location"), url, allowedHost, hop
                    )
                    when (outcome) {
                        is RedirectPolicy.Outcome.Follow ->
                            send(outcome.url, allowedHost, apiKey, method, body, hop + 1)
                        is RedirectPolicy.Outcome.Refuse -> {
                            AppLogger.w(TAG, "redirect refused: ${outcome.reason}")
                            Result.Refused(Reason.TRANSPORT, outcome.reason)
                        }
                    }
                }
                status == 403 -> Result.Refused(Reason.SERVER_DAILY_LIMIT)
                status == 429 -> Result.Refused(Reason.SERVER_RATE_LIMIT)
                status !in 200..299 -> Result.Refused(Reason.SERVER_REJECTED, status.toString())
                else -> readCapped(connection)
            }
        } catch (e: Exception) {
            // The message, not the exception: a stack trace here would carry the URL.
            AppLogger.w(TAG, "routing request failed: ${e.javaClass.simpleName}")
            Result.Refused(Reason.TRANSPORT)
        } finally {
            connection.disconnect()
        }
    }

    /** Refuses an oversized body rather than truncating it into a route that is not the route. */
    private fun readCapped(connection: HttpURLConnection): Result {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        connection.inputStream.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read == -1) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_RESPONSE_BYTES) {
                    return Result.Refused(Reason.UNREADABLE, "response too large")
                }
            }
        }
        return Result.Ok(buffer.toString(StandardCharsets.UTF_8.name()))
    }

    fun spentToday(nowMs: Long = System.currentTimeMillis()): Int = quota.spentToday(nowMs)

    companion object {
        /**
         * A place name a driver types contains spaces, accents and the occasional `&`. Encoded
         * here rather than at the call site, so no caller can build a query by hand.
         */
        fun encodeQuery(query: Map<String, String>): String =
            if (query.isEmpty()) "" else query.entries.joinToString("&", prefix = "?") { entry ->
                "${encode(entry.key)}=${encode(entry.value)}"
            }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private const val TAG = "RoutingTransport"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** A route with elevation is large; anything past this is not a route. */
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val HEADER_REMAINING = "x-ratelimit-remaining"
    }
}
