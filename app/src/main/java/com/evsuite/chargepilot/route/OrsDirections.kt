package com.evsuite.chargepilot.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The one OpenRouteService call this app makes, and the little of its answer that is used.
 *
 * `POST /v2/directions/driving-car/geojson` with `elevation=true`, which returns the geometry as
 * `[longitude, latitude, altitude]` triples — the elevation profile CP-050 needs and the thing
 * the head unit's own guidance cannot supply at any price.
 *
 * Turn instructions are not parsed. ChargePilot is not a navigation app, the car already has
 * one, and every field parsed is a field that can be malformed.
 *
 * Gson rather than `org.json`, because this project's unit tests run on the JVM with
 * `isReturnDefaultValues`, where `org.json` answers every call with a default and a parser test
 * would pass without parsing anything. A correctness gate that cannot run is not a gate.
 *
 * ORS data is OpenStreetMap under ODbL: the attribution the API returns is displayed wherever a
 * route is. That is a licence obligation, not a courtesy.
 */
object OrsDirections {

    const val PATH = "/openrouteservice/v2/directions/driving-car/geojson"

    /** A route with more points than this is not a route this screen can use. */
    const val MAX_POINTS = 20_000

    /**
     * How much the road has to move before it counts as climbing.
     *
     * The elevation behind these geometries is a sampled terrain model with metre-scale noise,
     * and a route is thousands of points long. Summing every positive difference would turn
     * that noise into hundreds of metres of climb that the road does not have, and CP-050 feeds
     * this straight into a charge forecast. A threshold discards the noise and keeps the col.
     */
    const val ELEVATION_THRESHOLD_METRES = 10.0

    data class Point(val longitude: Double, val latitude: Double, val altitudeMetres: Double?)

    data class Route(
        val distanceKm: Double,
        val durationMinutes: Double,
        val points: List<Point>,
        val attribution: String?,
    ) {
        /** Cumulative climb and descent, the only part of the profile a model needs. */
        val ascentMetres: Double? get() = elevationSplit()?.first
        val descentMetres: Double? get() = elevationSplit()?.second

        private fun elevationSplit(): Pair<Double, Double>? {
            var up = 0.0
            var down = 0.0
            var reference: Double? = null
            var seen = 0
            for (point in points) {
                val altitude = point.altitudeMetres ?: continue
                seen++
                val last = reference
                if (last == null) {
                    reference = altitude
                    continue
                }
                val delta = altitude - last
                if (delta > ELEVATION_THRESHOLD_METRES) {
                    up += delta
                    reference = altitude
                } else if (-delta > ELEVATION_THRESHOLD_METRES) {
                    down -= delta
                    reference = altitude
                }
            }
            return if (seen < 2) null else up to down
        }
    }

    /**
     * @param alternatives how many routes to ask for in total, 0 or 1 for just the best. ORS
     *   charges one request either way, so asking is free; parsing more is not.
     */
    fun requestBody(origin: Point, destination: Point, alternatives: Int = 0): String {
        val coordinates = JsonArray().apply {
            add(JsonArray().apply { add(origin.longitude); add(origin.latitude) })
            add(JsonArray().apply { add(destination.longitude); add(destination.latitude) })
        }
        val body = JsonObject().apply {
            add("coordinates", coordinates)
            addProperty("elevation", true)
            addProperty("instructions", false)
            addProperty("units", "km")
        }
        if (alternatives > 1) {
            body.add("alternative_routes", JsonObject().apply {
                addProperty("target_count", alternatives.coerceAtMost(3))
                addProperty("share_factor", 0.6)
                addProperty("weight_factor", 1.4)
            })
        }
        return body.toString()
    }

    /**
     * Every route in the answer, best first, or an empty list.
     *
     * A malformed response yields nothing rather than a partial route: half a geometry is a
     * route to somewhere the driver is not going, and it would look exactly like a real one.
     */
    fun parse(json: String): List<Route> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val attribution = root.getAsJsonObject("metadata")
            ?.get("attribution")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val features = root.getAsJsonArray("features") ?: return emptyList()
        features.mapNotNull { element ->
            (element as? JsonObject)?.let { feature(it, attribution) }
        }
    }.getOrDefault(emptyList())

    private fun feature(feature: JsonObject, attribution: String?): Route? {
        val summary = feature.getAsJsonObject("properties")?.getAsJsonObject("summary")
            ?: return null
        val distanceKm = summary.get("distance")?.asDouble ?: return null
        val durationSeconds = summary.get("duration")?.asDouble ?: return null
        if (!distanceKm.isFinite() || !durationSeconds.isFinite()) return null

        val coordinates = feature.getAsJsonObject("geometry")?.getAsJsonArray("coordinates")
            ?: return null
        if (coordinates.size() > MAX_POINTS) return null
        val points = ArrayList<Point>(coordinates.size())
        for (element in coordinates) {
            val triple = element as? JsonArray ?: return null
            if (triple.size() < 2) return null
            val longitude = triple[0].asDouble
            val latitude = triple[1].asDouble
            if (!longitude.isFinite() || !latitude.isFinite()) return null
            points.add(
                Point(
                    longitude = longitude,
                    latitude = latitude,
                    altitudeMetres = if (triple.size() >= 3) {
                        triple[2].asDouble.takeIf { it.isFinite() }
                    } else null,
                )
            )
        }
        if (points.size < 2) return null

        return Route(
            distanceKm = distanceKm,
            durationMinutes = durationSeconds / 60.0,
            points = points,
            attribution = attribution,
        )
    }
}
