package com.evsuite.chargepilot

import com.evsuite.hardware.saic.NavLegChain
import com.evsuite.hardware.saic.NavigationHandoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one thing in this application that asks the car to act without a driver touching anything.
 *
 * [NavLegChain] decides *when* a leg is over and is EVHardware's to test. This covers the app
 * half — the cadence it is ticked on, the fact that an unarmed tick costs nothing, and that a
 * reading the adapter refused to give is not passed on as "not guiding". Getting that last one
 * wrong spends the chain's grace against a question that was never asked, and the car is sent
 * onward from a charger it is still driving to.
 */
class NavLegsTest {

    private val stop = NavigationHandoff.Poi(44.9, 4.9, "Aire de Montélimar")
    private val destination = NavigationHandoff.Poi(43.3, 5.4, "Marseille")

    private val sent = mutableListOf<NavLegChain.Step.Send>()

    @Before
    fun setUp() {
        sent.clear()
        NavLegs.disarm()
    }

    @After
    fun tearDown() = NavLegs.disarm()

    /** One sampler second. Returns how many readings the chain was actually offered. */
    private fun ticks(count: Int, guiding: Boolean?): Int {
        var asked = 0
        repeat(count) {
            NavLegs.tick({ asked++; guiding }, { sent += it })
        }
        return asked
    }

    @Test
    fun `nothing armed costs nothing, not even a reading`() {
        assertEquals(0, ticks(50, guiding = true))
    }

    @Test
    fun `the car is asked once every five sampler seconds, not once a second`() {
        NavLegs.arm(listOf(stop, destination))
        assertEquals(2, ticks(10, guiding = true))
    }

    @Test
    fun `a leg that ends hands the next one over, once`() {
        NavLegs.arm(listOf(stop, destination))
        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = true)
        assertTrue(sent.isEmpty())

        // Guidance stops: the charger has been reached. One false reading is not an arrival, so
        // the handover comes on the settle count and not before it.
        ticks(5 * (NavLegChain.SETTLE_TICKS - 1), guiding = false)
        assertTrue("handed over before the chain settled", sent.isEmpty())
        ticks(5, guiding = false)
        assertEquals(1, sent.size)
        assertEquals(destination, sent.single().poi)
        assertEquals(2, sent.single().number)
        assertEquals(2, sent.single().count)

        // The last leg runs and ends: the chain is over, not looped back to the start.
        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = true)
        ticks(5 * (NavLegChain.SETTLE_TICKS + 2), guiding = false)
        assertEquals(1, sent.size)
    }

    @Test
    fun `an adapter that will not answer is not read as not guiding`() {
        NavLegs.arm(listOf(stop, destination))
        // Long past every grace the chain has, including the one that abandons a leg whose
        // guidance never started. A null must spend neither.
        ticks(5 * (NavLegChain.GIVE_UP_TICKS + NavLegChain.SETTLE_TICKS + 5), guiding = null)
        assertTrue(sent.isEmpty())

        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = true)
        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = false)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a leg whose guidance never starts is abandoned, never chained onto`() {
        NavLegs.arm(listOf(stop, destination))
        ticks(5 * (NavLegChain.GIVE_UP_TICKS + 2), guiding = false)
        assertTrue("sent the car onward from a charger it never reached", sent.isEmpty())
    }

    @Test
    fun `a trip with one leg arms no chain, and a new handoff replaces the old one`() {
        NavLegs.arm(listOf(destination))
        ticks(5 * (NavLegChain.SETTLE_TICKS + 2), guiding = true)
        ticks(5 * (NavLegChain.SETTLE_TICKS + 2), guiding = false)
        assertTrue(sent.isEmpty())

        NavLegs.arm(listOf(stop, destination))
        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = true)
        ticks(5 * NavLegChain.SETTLE_TICKS, guiding = false)
        assertEquals(1, sent.size)

        NavLegs.disarm()
        assertEquals(0, ticks(20, guiding = false))
    }

    @Test
    fun `arming with nothing watches nothing`() {
        NavLegs.arm(emptyList())
        assertEquals(0, ticks(20, guiding = true))
    }
}
