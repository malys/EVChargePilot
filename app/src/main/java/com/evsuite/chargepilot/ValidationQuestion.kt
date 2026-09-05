package com.evsuite.chargepilot

/**
 * The questions that only a car can answer, named once so a bundle can be read against them.
 *
 * Eleven tickets ended "verified on JVM, never on the vehicle". Each named a different
 * question, and answered one at a time each costs a drive. CP-055 groups them into one drive,
 * which only works if every probe is armed before departure and records itself: an instrument
 * that needs a tap at 130 km/h is a hazard, not a probe.
 *
 * [firesWhen] exists so that an empty answer is still an answer. A bundle that says nothing
 * about question five leaves a reader guessing between "no chargers came back" and "the search
 * never ran"; a bundle that says "records when a plan needs a stop and a charger search runs,
 * and it never did" has told them which.
 */
enum class ValidationQuestion(
    val id: String,
    val ticket: String,
    val question: String,
    val firesWhen: String,
) {
    DESTINATION(
        "Q1",
        "CP-051",
        "Do IMapService transactions 38 and 39 carry the destination?",
        "the application starts, the first time guidance is heard, and on every export",
    ),
    LOCATION_GRANT(
        "Q2",
        "CP-043",
        "Is fine location already granted at startup, without a prompt, " +
            "because CAR_SPEED shares the LOCATION permission group?",
        "the application starts, before this process has requested anything",
    ),
    ROUTE_SECTIONS(
        "Q3",
        "CP-049",
        "Do real routing steps produce sections that look like roads?",
        "a route request comes back and is parsed",
    ),
    ROUTE_ALTERNATIVES(
        "Q4",
        "CP-049",
        "Does the configured instance accept alternative_routes, and how many come back?",
        "a route request completes, whether it is answered or refused",
    ),
    CHARGERS(
        "Q5",
        "CP-048",
        "Does Open Charge Map return usable chargers along a French motorway?",
        "a plan needs a stop, so a charger search runs on the road ahead of it",
    ),
    SOC_SEGMENTS(
        "Q6",
        "CP-052",
        "Do the charge segments the consumption model needs appear on a real drive?",
        "the charge-stop screen loads the trip history and refits the model",
    ),
    WHAT_IF(
        "Q7",
        "CP-049",
        "Does the route what-if produce rows on this firmware, or a refusal?",
        "a route comes back and the what-if is computed from it",
    ),
    NAVIGATION_HANDOFF(
        "Q9",
        "CP-056",
        "Does anything on this head unit answer a navigation intent, so a chosen route " +
            "could be handed to the car's own navigation instead of copied by hand?",
        "the application starts; the intents are resolved, never sent",
    ),
    PLAN_DRIFT(
        "Q10",
        "CP-058",
        "Does the adapter's odometer move during a drive, and does the car keep answering a " +
            "remaining distance for a destination another app handed it?",
        "a plan is being followed, once a minute, whatever the verdict on it",
    ),
    LOCATION_FALLBACK(
        "Q8",
        "CP-046",
        "Is a location refusal survivable from end to end?",
        "a route is asked for while the fine grant or the position is missing",
    ),
}
