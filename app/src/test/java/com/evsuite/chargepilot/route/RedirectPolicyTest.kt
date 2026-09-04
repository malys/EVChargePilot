package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectPolicyTest {

    private val host = "api.openrouteservice.org"
    private val from = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"

    private fun refusal(outcome: RedirectPolicy.Outcome): String {
        assertTrue("expected a refusal, got $outcome", outcome is RedirectPolicy.Outcome.Refuse)
        return (outcome as RedirectPolicy.Outcome.Refuse).reason
    }

    @Test
    fun `an https to http redirect is a man in the middle and is refused`() {
        assertEquals(
            "redirect leaves https",
            refusal(RedirectPolicy.evaluate("http://api.openrouteservice.org/v2", from, host, 0)),
        )
    }

    @Test
    fun `a redirect to another host is refused`() {
        assertEquals(
            "redirect to another host",
            refusal(RedirectPolicy.evaluate("https://elsewhere.example/v2", from, host, 0)),
        )
    }

    @Test
    fun `a host that merely ends with the allowed one is another host`() {
        assertEquals(
            "redirect to another host",
            refusal(
                RedirectPolicy.evaluate(
                    "https://api.openrouteservice.org.attacker.example/v2", from, host, 0
                )
            ),
        )
    }

    @Test
    fun `a relative redirect on the same host is followed`() {
        val outcome = RedirectPolicy.evaluate("/v2/directions/other", from, host, 0)
        assertEquals(
            RedirectPolicy.Outcome.Follow("https://api.openrouteservice.org/v2/directions/other"),
            outcome,
        )
    }

    @Test
    fun `a redirect chain is bounded`() {
        assertEquals(
            "too many redirects",
            refusal(
                RedirectPolicy.evaluate(
                    "https://api.openrouteservice.org/v2", from, host, RedirectPolicy.MAX_HOPS
                )
            ),
        )
    }

    @Test
    fun `a redirect without a location is refused rather than retried`() {
        assertEquals(
            "redirect without a location",
            refusal(RedirectPolicy.evaluate(null, from, host, 0)),
        )
    }

    @Test
    fun `the first url is held to the same rule as every hop`() {
        assertNull(RedirectPolicy.refuseInitial(from, host))
        assertEquals(
            "not https",
            RedirectPolicy.refuseInitial("http://api.openrouteservice.org/v2", host),
        )
        assertEquals(
            "not the configured host",
            RedirectPolicy.refuseInitial("https://elsewhere.example/v2", host),
        )
        assertEquals(
            "credentials in the URL",
            RedirectPolicy.refuseInitial("https://u:p@api.openrouteservice.org/v2", host),
        )
    }
}
