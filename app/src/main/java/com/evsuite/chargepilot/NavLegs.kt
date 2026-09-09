package com.evsuite.chargepilot

import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.saic.NavigationHandoff
import com.evsuite.hardware.saic.NavLegChain
import com.evsuite.hardware.saic.SaicNavGuidance

/**
 * The plan the car is currently being driven through, leg by leg.
 *
 * **Why the app holds this at all.** `goTo` is the one command on this head unit seen to start
 * guidance, and it carries a single point. A plan with a charging stop on the way is therefore
 * two handovers, not one: the stop while it is being driven to, then the destination once it has
 * been reached. [NavLegChain] decides *when*; this decides where the reading comes from and where
 * the command goes.
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
        samples = 0
    }

    /**
     * One sampler tick. Reads the car's guidance flag at most once every [TICK_SAMPLES] samples.
     *
     * Call from a worker thread. Cheap when nothing is armed: a volatile read and a return.
     */
    fun tick() {
        val current = chain ?: return
        if (++samples < TICK_SAMPLES) return
        samples = 0
        // Null is "the adapter did not answer", which is not the same as "not guiding". Passing
        // it on as false would spend the chain's grace against a question that was never asked.
        val guiding = runCatching { SaicNavGuidance.isMapNavigating() }.getOrNull() ?: return
        when (val step = current.tick(guiding)) {
            is NavLegChain.Step.Wait -> Unit
            is NavLegChain.Step.Send -> send(step)
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

    private fun send(step: NavLegChain.Step.Send) {
        val sent = runCatching { SaicNavGuidance.goTo(step.poi) }.getOrDefault(false)
        AppLogger.i(TAG, "leg ${step.number}/${step.count} handed over; accepted=$sent")
        ValidationProbe.record(ValidationQuestion.NAVIGATION_HANDOFF) {
            "leg ${step.number} of ${step.count} handed over on arrival at the previous one, " +
                "adapter accepted=$sent"
        }
    }

    /** One reading every five seconds: the sampler runs at 1 Hz and arrival is not a fast event. */
    private const val TICK_SAMPLES = 5

    private const val TAG = "NavLegs"
}
