package com.evsuite.chargepilot.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingQuotaTest {

    private val start = 1_700_000_000_000L

    private fun wait(verdict: RoutingQuota.Verdict): RoutingQuota.Verdict.Wait {
        assertTrue("expected a refusal, got $verdict", verdict is RoutingQuota.Verdict.Wait)
        return verdict as RoutingQuota.Verdict.Wait
    }

    @Test
    fun `the minute limit is the one a recompute loop would hit`() {
        val quota = RoutingQuota()
        repeat(RoutingQuota.DIRECTIONS_PER_MINUTE) { quota.record(start) }
        val refusal = wait(quota.check(start))
        assertEquals(RoutingQuota.Window.MINUTE, refusal.window)
        assertTrue("wait must fit in the window", refusal.seconds in 1..60)
    }

    @Test
    fun `the minute window rolls, it does not reset on a clock minute`() {
        val quota = RoutingQuota()
        repeat(RoutingQuota.DIRECTIONS_PER_MINUTE) { quota.record(start) }
        assertTrue(quota.check(start + 59_999L) is RoutingQuota.Verdict.Wait)
        assertSame(RoutingQuota.Verdict.Allowed, quota.check(start + 60_001L))
    }

    @Test
    fun `the day's allowance is spent at two thousand`() {
        val quota = RoutingQuota()
        // Two seconds apart, so the minute limit is never the one that refuses.
        repeat(RoutingQuota.DIRECTIONS_PER_DAY) { quota.record(start + it * 2_000L) }
        val last = start + RoutingQuota.DIRECTIONS_PER_DAY * 2_000L
        assertEquals(RoutingQuota.DIRECTIONS_PER_DAY, quota.spentToday(last))
        assertEquals(RoutingQuota.Window.DAY, wait(quota.check(last)).window)
    }

    @Test
    fun `the day rolls from the first request, not from midnight`() {
        val quota = RoutingQuota(dayLimit = 2)
        quota.record(start)
        quota.record(start + 1_000L)
        assertTrue(quota.check(start + 2_000L) is RoutingQuota.Verdict.Wait)

        val nextDay = start + 24 * 60 * 60 * 1000L + 1L
        assertSame(RoutingQuota.Verdict.Allowed, quota.check(nextDay))
        assertEquals(1, quota.spentToday(nextDay))
    }

    @Test
    fun `the server's own count wins, because another client may share the key`() {
        val quota = RoutingQuota()
        quota.observe(0, start)
        assertEquals(RoutingQuota.Window.DAY, wait(quota.check(start)).window)

        quota.observe(5, start)
        assertSame(RoutingQuota.Verdict.Allowed, quota.check(start))
    }

    @Test
    fun `a header we could not read leaves the local count alone`() {
        val quota = RoutingQuota()
        quota.observe(0, start)
        quota.observe(null, start)
        assertEquals(RoutingQuota.Window.DAY, wait(quota.check(start)).window)
    }

    @Test
    fun `the server's count is not believed for ever, or nothing can ask it again`() {
        val quota = RoutingQuota()
        quota.observe(0, start)
        assertEquals(RoutingQuota.Window.DAY, wait(quota.check(start)).window)
        // Held for ever this is a deadlock: refusing locally means no request leaves, and only
        // a request brings back the header that would reopen the gate. One is let through when
        // the trust window ends, and the screen works again by itself.
        val later = start + RoutingQuota.SERVER_TRUST_MS
        assertSame(RoutingQuota.Verdict.Allowed, quota.check(later))
    }
}
