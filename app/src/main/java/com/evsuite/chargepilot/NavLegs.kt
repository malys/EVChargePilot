package com.evsuite.chargepilot

import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.saic.NavigationHandoff
import com.evsuite.hardware.saic.NavLegChain
import com.evsuite.hardware.saic.SaicNavGuidance

/**
 * The plan the car is currently being driven through, leg by leg.
 *
 * **Why the app holds this at all.** A plan with a charging stop is a stop and then a destination,
 * and nothing on this head unit has been seen to drive both from one handover. So it is two
 * handovers: the stop while it is being driven to, then the destination once it has been reached.
 * [NavLegChain] decides *when*; this decides where the reading comes from and where the command
 * goes.
 *
 * **In memory, for the length of the drive.** Legs are place names and coordinates. Nothing here
 * touches a file: the followed-plan store on the other side of this trip deliberately keeps no
 * destination and no coordinate, and a chain written to disk would be the same data arriving by
 * another door. A process that dies loses the chain and the driver hands the rest over from the
 * screen, which is what they did before this existed.
 *
 * **Ticked from the sampler thread.** [tick] does binder reads and a binder write, and the
 * adapter's fan-out holds a lock over every registered listener — it must never be called on the
 * main thread. The trip recorder's one-second sampler is already a worker with nothing else to do
 * between two reads, so the chain rides along there rather than starting a timer of its own.
 */
object NavLegs {

    @Volatile
    private var chain: NavLegChain? = null

    /**
     * Whether a handoff of ours is out there, chain or no chain.
     *
     * A trip with one leg arms no chain and still needs watching: the question the drive of
     * 2026-09-09 left open is whether `isMapNavigating` stays true for a whole route handed over
     * through `startNavFromEVRout`, when the adapter's remaining distance went to nothing seven
     * minutes in and its notification listener heard nothing at all. If that flag is as shaky as
     * its siblings the chain cannot be built on it, and one destination-only drive says so.
     */
    @Volatile
    private var watching = false

    /** The last flag reading, so only a change is written down rather than one line a minute. */
    private var lastGuiding: Boolean? = null

    /** How many sampler ticks since the last chain reading. */
    private var samples = 0

    /**
     * Arms the chain for a plan the driver just handed over.
     *
     * [legs] is the whole trip in order, first leg first — the leg the tap itself sent included,
     * because the chain counts from it. A trip of fewer than two points arms nothing: there is
     * no second leg to start and a chain that can never fire is a timer nobody needs.
     */
    fun arm(legs: List<NavigationHandoff.Poi>) {
        chain = if (legs.size >= 2) NavLegChain(legs) else null
        watching = legs.isNotEmpty()
        lastGuiding = null
        samples = 0
        // No name and no coordinate: this line leaves the car on a USB stick. A count of legs
        // is a fact about a plan's shape, not about where anyone is going.
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            if (legs.size >= 2) "chained handoff armed: ${legs.size} legs, leg 1 sent by the tap"
            else "no chain armed: ${legs.size} leg, nothing follows it"
        }
    }

    /** Forgets the chain. A new handoff replaces the old one rather than racing it. */
    fun disarm() {
        chain = null
        watching = false
        lastGuiding = null
        samples = 0
    }

    /**
     * One sampler tick. Reads the car's guidance flag at most once every [TICK_SAMPLES] samples.
     *
     * Call from a worker thread. Cheap when nothing is armed: a volatile read and a return.
     */
    fun tick() = tick(::readGuiding, ::send)

    /**
     * The same tick with its two vehicle calls handed in, so the cadence, the grace and the
     * chaining can be driven from a JVM test. Both defaults are the real binder calls; nothing
     * else is stubbed, because everything else here is the decision under test.
     *
     * The parameters are named apart from the private members they default to on purpose: a
     * parameter of function type loses name resolution to a member function of the same name, so
     * `send(step)` inside here would quietly go to the binder in a test that passed its own.
     */
    internal fun tick(guidingNow: () -> Boolean?, handOver: (NavLegChain.Step.Send) -> Unit) {
        if (!watching) return
        if (++samples < TICK_SAMPLES) return
        samples = 0
        // Null is "the adapter did not answer", which is not the same as "not guiding". Passing
        // it on as false would spend the chain's grace against a question that was never asked.
        val guiding = guidingNow() ?: return
        if (guiding != lastGuiding) {
            lastGuiding = guiding
            ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
                "after the handoff, isMapNavigating=$guiding" +
                    (chain?.let { " (a chain is armed)" } ?: " (nothing to chain)")
            }
        }
        val current = chain ?: return
        when (val step = current.tick(guiding)) {
            is NavLegChain.Step.Wait -> Unit
            is NavLegChain.Step.Send -> handOver(step)
            is NavLegChain.Step.Done -> {
                chain = null
                AppLogger.i(TAG, "leg chain finished; arrived=${step.arrived}")
                ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
                    if (step.arrived) "chained handoff done: the last leg's guidance ended"
                    else "chained handoff abandoned: guidance never started for the current leg"
                }
            }
        }
    }

    /**
     * Hands the next leg over through the same ladder the tap uses, in the same order.
     *
     * The route handoff first: on 2026-09-09 it is the channel that started guidance on this car,
     * where `goTo` was accepted and ignored. A single point with an empty pathway is a route with
     * one end, which is exactly what a leg is. `goTo` stays as the rung below for a head unit that
     * behaves the other way round — this code runs unattended on a road, and a leg that goes
     * nowhere strands the driver at a charger with no guidance onward.
     */
    private fun readGuiding(): Boolean? =
        runCatching { SaicNavGuidance.isMapNavigating() }.getOrNull()

    private fun send(step: NavLegChain.Step.Send) {
        val route = runCatching {
            SaicNavGuidance.startNavFromEvRoute(step.poi, emptyList())
        }.getOrDefault(false)
        val goTo = if (route) false else runCatching {
            SaicNavGuidance.goTo(step.poi)
        }.getOrDefault(false)
        AppLogger.i(
            TAG,
            "leg ${step.number}/${step.count} handed over; route=$route goTo=$goTo",
        )
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "leg ${step.number} of ${step.count} handed over on arrival at the previous one: " +
                "route taken=$route, goTo taken=$goTo"
        }
    }

    /** One reading every five seconds: the sampler runs at 1 Hz and arrival is not a fast event. */
    private const val TICK_SAMPLES = 5

    private const val TAG = "NavLegs"
}
