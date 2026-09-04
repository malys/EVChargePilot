package com.evsuite.chargepilot.route

import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
 * `GET /geocode/search`, with the key in the `Authorization` header like every other request
 * here. ORS documents this endpoint with the key in the query string; a key in a URL reaches
 * access logs and `Referer` headers, so this app does not send one that way. If the header is
 * refused, the screen says the server rejected the request and the driver keeps a key that
 * never leaked — which is the failure this project prefers.
 *
 * **Quota, and the honesty about it.** Directions limits were read from ORS's own documentation
 * (2000 a day, 40 a minute). The geocoding numbers below could not be: on 2026-09-04 the plans
 * page is a JavaScript application that serves no readable figures, and the numbers commonly
 * published for it — 1000 a day, 100 a minute — come from third parties. They are used here as
 * an assumed ceiling, which is safe in the direction that matters: if the real allowance is
 * larger nothing breaks, and [RoutingQuota.observe] corrects the count from the server's own
 * header the moment a request comes back.
 */
object OrsGeocode {

    const val PATH = "/geocode/search"

    /** Assumed, not verified — see the class note. */
    const val PER_DAY = 1000
    const val PER_MINUTE = 100

    /** More than a driver reads on a head unit while parked. */
    const val MAX_RESULTS = 5

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
