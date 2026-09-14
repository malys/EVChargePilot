package com.evsuite.chargepilot.route

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.Normalizer
import java.util.Locale

/**
 * Turning what the driver typed into the coordinates a route needs.
 *
 * CP-047 left one question open — *where does the destination come from* — and preferred a
 * destination read from the car's own navigation. That answer needs the vehicle: `IMapService`
 * transactions 38/39 were identified during CP-040 and never called. Until somebody sits in the
 * car and probes them, nothing downstream of a route can be exercised at all, so this takes the
 * ticket's second candidate: the driver types a place, ORS geocodes it, the driver confirms
 * which of the answers they meant. If the probe succeeds, this stays as the way to route
 * somewhere the car is not already navigating to.
 *
 * `GET /pelias/v1/search`, with the key in the `Authorization` header like every other request
 * here. ORS documents this endpoint with the key in the query string; a key in a URL reaches
 * access logs and `Referer` headers, so this app does not send one that way. If the header is
 * refused, the screen says the server rejected the request and the driver keeps a key that
 * never leaked — which is the failure this project prefers.
 *
 * **Quota, and the honesty about it.** Directions limits were read from ORS's own documentation
 * (2000 a day, 40 a minute). The geocoding numbers below could not be: on 2026-09-04 the plans
 * page is a JavaScript application that serves no readable figures, and the numbers commonly
 * published for it — 1000 a day, 100 a minute — come from third parties. The day figure is used
 * as an assumed ceiling, safe in the direction that matters: if the real allowance is larger
 * nothing breaks, and [RoutingQuota.observe] corrects the count from the server's own header the
 * moment a request comes back. **The per-minute figure is not**, and [RoutingQuota.observe]
 * does not correct it — only the day count reads the server's header, because a shared key can
 * be spent by another client between two of our own requests, which a minute-sized window
 * rarely lives long enough to matter for. Typing one destination on a head-unit keyboard, with
 * autocomplete firing per pause, is enough distinct prefixes to reach a wrong-and-too-generous
 * per-minute assumption before the address is finished — the driver then spends the rest of that
 * minute refused. [PER_MINUTE] is kept at [RoutingQuota.DIRECTIONS_PER_MINUTE], the one number
 * here with real confirmation, rather than a third-party guess this app cannot check.
 */
object OrsGeocode {

    /**
     * Pelias, not openrouteservice: geocoding was never part of the ORS software and HeiGIT's
     * new host says so out loud. The old `/geocode/search` lives only on the host being
     * switched off on 2026-09-28.
     */
    const val PATH = "/pelias/v1/search"

    /**
     * Pelias's own type-ahead endpoint. It answers a *prefix*, which is what a driver has while
     * they are still typing, and it is the reason a destination no longer needs a finished
     * address and a tap on a button: the answers arrive on the way. Same host, same key, same
     * header, same transport — one socket to audit, which is [RoutingTransport]'s whole rule.
     */
    const val AUTOCOMPLETE_PATH = "/pelias/v1/autocomplete"

    /** Under three letters a prefix matches half a country, and every request is quota. */
    const val MIN_SUGGEST_CHARS = 3

    /**
     * A pause in typing, not a keystroke — long enough that a finger tapping a head-unit
     * keyboard, where the gap between two letters routinely exceeds a physical keyboard's,
     * still coalesces into one request instead of firing after nearly every letter.
     *
     * 500ms measured as unusable: a driver typing one destination spent the whole local
     * per-minute allowance before the address was finished, because a touchscreen's own
     * typing cadence is slower than what that number assumed.
     */
    const val SUGGEST_DEBOUNCE_MS = 900L

    /**
     * Assumed, not verified — see the class note. Brought down to the one number this app
     * does have real confirmation for, [RoutingQuota.DIRECTIONS_PER_MINUTE]: the geocode
     * endpoint's own per-minute figure was a guess that let this local throttle wave through
     * requests the server was already refusing, so every one of them still spent a wait.
     */
    const val PER_DAY = 1000
    const val PER_MINUTE = RoutingQuota.DIRECTIONS_PER_MINUTE

    /** More than a driver reads on a head unit while parked. */
    const val MAX_RESULTS = 5

    /**
     * Whether what is in the box is worth a request: long enough to mean something, and not the
     * text already asked about — a driver moving the cursor, or fixing capitalisation, has not
     * asked a new question.
     */
    fun shouldSuggest(text: String, lastQueried: String?): Boolean {
        val trimmed = text.trim()
        return trimmed.length >= MIN_SUGGEST_CHARS &&
            !trimmed.equals(lastQueried?.trim(), ignoreCase = true)
    }

    fun quota(): RoutingQuota = RoutingQuota(dayLimit = PER_DAY, minuteLimit = PER_MINUTE)

    data class Place(val label: String, val longitude: Double, val latitude: Double)

    /**
     * @param near where the car is, when it is known. It biases the answers towards the driver
     *   rather than towards the largest city of that name, and it is the same position a route
     *   request carries — so it tells the service nothing the next call would not.
     */
    fun query(text: String, near: LocationSource.Fix?): Map<String, String> = buildMap {
        put("text", text.trim())
        put("size", MAX_RESULTS.toString())
        if (near != null) {
            put("focus.point.lon", near.longitude.toString())
            put("focus.point.lat", near.latitude.toString())
        }
    }

    /**
     * The saved places that match what is being typed, best-effort and free: matched here,
     * against what is already on disk, so the shortest destination of all — one the driver has
     * been to before — costs no request and no wait.
     *
     * Every word typed has to appear somewhere in the label, in any order, with accents folded
     * away: this head unit's keyboard is what turns Écully into "ecully", and a saved favourite
     * that hides because of an accent is worse than no filter at all. Blank text matches
     * everything, which is the list the screen opens on.
     */
    fun matching(places: List<Place>, text: String): List<Place> {
        val words = fold(text).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return places
        return places.filter { place ->
            val label = fold(place.label)
            words.all { label.contains(it) }
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** Combining marks, after NFD split every accented letter into letter + mark. */
    private val DIACRITICS = Regex("\\p{Mn}+")

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.US), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")

    /** The places the service recognised, best first, or an empty list. */
    fun parse(json: String): List<Place> = runCatching {
        val features = JsonParser.parseString(json).asJsonObject.getAsJsonArray("features")
            ?: return emptyList()
        features.take(MAX_RESULTS).mapNotNull { element ->
            (element as? JsonObject)?.let(::place)
        }
    }.getOrDefault(emptyList())

    private fun place(feature: JsonObject): Place? {
        val label = feature.getAsJsonObject("properties")?.get("label")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return null
        val coordinates = feature.getAsJsonObject("geometry")?.getAsJsonArray("coordinates")
            ?: return null
        if (coordinates.size() < 2) return null
        val longitude = coordinates[0].asDouble
        val latitude = coordinates[1].asDouble
        if (!longitude.isFinite() || !latitude.isFinite()) return null
        if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null
        return Place(label, longitude, latitude)
    }
}
