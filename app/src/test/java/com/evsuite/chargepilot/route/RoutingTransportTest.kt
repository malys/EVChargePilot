package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of the only socket this application opens.
 *
 * Everything asserted here happens **before** a connection is attempted, which is what makes it
 * testable at all and also what makes it worth testing: the app's whole network argument is that
 * nothing reaches the socket unchecked. [RedirectPolicy] is tested on its own; this pins that
 * [RoutingTransport] actually consults it, and that the quota refuses locally rather than
 * spending a request to be told no.
 *
 * The quota cases do let a request through the gate, and they aim it at `https://127.0.0.1:1` —
 * refused by the loopback stack before a packet leaves the machine, and no DNS lookup on the way.
 * Nothing in this file contacts a real service.
 *
 * What is deliberately not covered: anything past a successful `connect()`. Reaching it needs an
 * `https` server with a certificate this JVM trusts, which buys less than it costs. `BUSY` is in
 * the same bracket — proving it needs a request held open.
 */
class RoutingTransportTest {

    /** Refused by the loopback stack, instantly, without leaving the machine. */
    private val deadEnd = "https://127.0.0.1:1"

    private val transport = RoutingTransport()

    private fun creds(
        key: String = "a-key",
        base: String = "https://api.heigit.org",
    ) = RoutingCredentials.Values(key, base)

    private fun refusal(result: RoutingTransport.Result): RoutingTransport.Result.Refused {
        assertTrue("expected a refusal, got $result", result is RoutingTransport.Result.Refused)
        return result as RoutingTransport.Result.Refused
    }

    @Test
    fun `no credentials is not an error, it is an unconfigured app`() {
        assertEquals(
            RoutingTransport.Reason.NOT_CONFIGURED,
            refusal(transport.post(null, "/v2/x", "{}")).reason,
        )
        assertEquals(
            RoutingTransport.Reason.NOT_CONFIGURED,
            refusal(transport.post(creds(key = "   "), "/v2/x", "{}")).reason,
        )
    }

    @Test
    fun `a base URL with no host never becomes a request`() {
        assertEquals(
            RoutingTransport.Reason.NOT_CONFIGURED,
            refusal(transport.post(creds(base = "not a url at all"), "/v2/x", "{}")).reason,
        )
    }

    @Test
    fun `plain http is refused before the socket, not connected to and then regretted`() {
        val refused = refusal(transport.post(creds(base = "http://api.heigit.org"), "/v2/x", "{}"))
        assertEquals(RoutingTransport.Reason.TRANSPORT, refused.reason)
        assertEquals("not https", refused.detail)
    }

    @Test
    fun `a path that walks the request onto another host is refused`() {
        // The path is appended to the configured base, so an absolute URL smuggled into it is the
        // shape that would leave the allowlist without a redirect being involved at all.
        assertEquals(
            RoutingTransport.Reason.TRANSPORT,
            refusal(transport.get(creds(), "https://attacker.example/v2/x", emptyMap())).reason,
        )
    }

    @Test
    fun `the minute allowance is refused here rather than by the server`() {
        val minute = RoutingTransport(RoutingQuota(dayLimit = 100, minuteLimit = 1))
        val now = 1_700_000_000_000L
        // The first call passes the gate and then fails at the socket, which still spends the
        // allowance — the point is that the second one never gets that far.
        minute.post(creds(base = deadEnd), "/v2/x", "{}", now)
        assertEquals(1, minute.spentToday(now))
        val refused = refusal(minute.post(creds(base = deadEnd), "/v2/x", "{}", now))
        assertEquals(RoutingTransport.Reason.QUOTA_MINUTE, refused.reason)
        assertEquals(1, minute.spentToday(now))
    }

    @Test
    fun `the day allowance is refused with its own reason, so the screen can say which`() {
        val day = RoutingTransport(RoutingQuota(dayLimit = 1, minuteLimit = 100))
        val now = 1_700_000_000_000L
        day.post(creds(base = deadEnd), "/v2/x", "{}", now)
        assertEquals(
            RoutingTransport.Reason.QUOTA_DAY,
            refusal(day.post(creds(base = deadEnd), "/v2/x", "{}", now)).reason,
        )
    }

    @Test
    fun `a request refused at the gate is never counted against the driver's allowance`() {
        val guarded = RoutingTransport()
        val now = 1_700_000_000_000L
        guarded.post(null, "/v2/x", "{}", now)
        guarded.post(creds(base = "http://api.heigit.org"), "/v2/x", "{}", now)
        guarded.post(creds(base = "not a url at all"), "/v2/x", "{}", now)
        assertEquals(0, guarded.spentToday(now))
    }

    @Test
    fun `what a driver types is encoded here, so no caller can build a query by hand`() {
        val query = RoutingTransport.encodeQuery(
            mapOf("text" to "Aix-en-Provence & Marseille", "size" to "5")
        )
        assertTrue(query.startsWith("?"))
        assertTrue(query, query.contains("text=Aix-en-Provence+%26+Marseille"))
        assertTrue(query, query.contains("&size=5"))
        assertEquals("", RoutingTransport.encodeQuery(emptyMap()))
    }

    @Test
    fun `an accented place name survives the query it is put into`() {
        val query = RoutingTransport.encodeQuery(mapOf("text" to "Montélimar"))
        assertEquals("?text=Mont%C3%A9limar", query)
        assertFalse(query.contains("é"))
    }
}
