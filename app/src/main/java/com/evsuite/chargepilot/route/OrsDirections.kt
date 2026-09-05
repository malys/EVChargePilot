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
 * Turn instructions are asked for and only their arithmetic is kept — a distance, a duration
 * and a road name per step. ChargePilot is not a navigation app and the car already has one;
 * what CP-049 needs from the steps is where the road ahead is fast, and the router's own
 * duration over its own distance says that without a speed-limit dataset. No manoeuvre text is
 * read, and every field parsed is a field that can be malformed.
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

    /**
     * A piece of the road ahead, with the pace the router expects on it.
     *
     * The implied speed is the router's own duration over its own distance, which is how CP-049
     * knows where slowing down is a choice: no speed-limit dataset is consulted, because the
     * question is not what the limit is but how fast this road is being driven.
     */
    data class Section(val distanceKm: Double, val durationMinutes: Double, val road: String?) {
        val impliedSpeedKmh: Double?
            get() = if (durationMinutes > 0.0 && distanceKm > 0.0) {
                distanceKm / (durationMinutes / 60.0)
            } else {
                null
            }
    }

    data class Route(
        val distanceKm: Double,
        val durationMinutes: Double,
        val points: List<Point>,
        val attribution: String?,
        val sections: List<Section> = emptyList(),
    ) {
        /** The road this route spends most of its length on, for telling two of them apart. */
        val viaLabel: String?
            get() = sections.filter { !it.road.isNullOrBlank() }
                .maxByOrNull { it.distanceKm }?.road

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
    /**
     * @param avoidMotorways CP-057's third row. A road the driver would not otherwise take is
     *   worth one request only when the motorway plan needs a charging stop and this one might
     *   not — an hour of departmental roads to arrive with the same charge is not a choice.
     */
    fun requestBody(
        origin: Point,
        destination: Point,
        alternatives: Int = 0,
        avoidMotorways: Boolean = false,
    ): String {
        val coordinates = JsonArray().apply {
            add(JsonArray().apply { add(origin.longitude); add(origin.latitude) })
            add(JsonArray().apply { add(destination.longitude); add(destination.latitude) })
        }
        val body = JsonObject().apply {
            add("coordinates", coordinates)
            addProperty("elevation", true)
            // CP-049 needs the road ahead broken into sections with a duration each, and the
            // steps are where that lives. The geometry still dwarfs them.
            addProperty("instructions", true)
            addProperty("units", "km")
        }
        if (avoidMotorways) {
            body.add("options", JsonObject().apply {
                add("avoid_features", JsonArray().apply { add("highways") })
            })
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

    /**
     * The steps, flattened, and only the ones that describe driving somewhere.
     *
     * A missing or malformed step list yields an empty list rather than a failed parse: the
     * route is still a route without it, and only the what-if loses its input.
     */
    private fun sections(feature: JsonObject): List<Section> = runCatching {
        val segments = feature.getAsJsonObject("properties")?.getAsJsonArray("segments")
            ?: return emptyList()
        val out = ArrayList<Section>()
        for (segment in segments) {
            val steps = (segment as? JsonObject)?.getAsJsonArray("steps") ?: continue
            for (element in steps) {
                val step = element as? JsonObject ?: continue
                val distanceKm = step.get("distance")?.asDouble ?: continue
                val durationSeconds = step.get("duration")?.asDouble ?: continue
                if (!distanceKm.isFinite() || !durationSeconds.isFinite()) continue
                if (distanceKm <= 0.0 || durationSeconds <= 0.0) continue
                out.add(
                    Section(
                        distanceKm = distanceKm,
                        durationMinutes = durationSeconds / 60.0,
                        road = step.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() && it != "-" },
                    )
                )
            }
        }
        out
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
            sections = sections(feature),
        )
    }
}
