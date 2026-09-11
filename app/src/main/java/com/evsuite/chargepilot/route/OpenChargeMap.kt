package com.evsuite.chargepilot.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where the charging stop actually is.
 *
 * CP-042 answers *stop in N kilometres*; this answers *there*. CP-048 chose Open Charge Map over
 * the routing engine's own POI service for two reasons neither of which could be worked around:
 * the POI service returns no connector type and no power, so an MG4 cannot be told what it can
 * use, and it returns no timestamp, so a charger cannot be shown with the age of its record.
 * A charger dataset is wrong the day it is published, and an app that hides that is making a
 * promise the data cannot keep.
 *
 * **What leaves the car is a window, not the trip.** The query carries the stretch of road
 * around the planned stop and nothing else — see [query]. The service learns a piece of
 * road in the middle of a journey; it does not learn where the driver started or is going.
 *
 * The key is a second one the driver supplies, in an `X-API-Key` header. OCM publishes no
 * numeric quota and instead reserves the right to ban callers making excessive use, so the app
 * stays far away from a limit it cannot see: [quota] is deliberately small and every request
 * follows a driver action.
 */
object OpenChargeMap {

    const val DEFAULT_BASE_URL = "https://api.openchargemap.io"

    const val PATH = "/v3/poi/"

    /** OCM's own header name, case sensitive. */
    const val HEADER = "X-API-Key"

    /**
     * Connector IDs an MG4 can use, from OCM's published reference data (`ocm-data`,
     * `reference.json`, read 2026-09-04). 32 is CCS (Type 1) and is deliberately absent.
     */
    val USABLE_CONNECTORS = setOf(25, 1036, 33)

    /** Status IDs that mean "do not send anyone here", from the same reference data. */
    val UNUSABLE_STATUS = setOf(100, 150, 200, 210)

    /** A head unit list, not a database dump. */
    const val MAX_RESULTS = 10

    /** How far off the road a charger may be and still be on this route. */
    const val CORRIDOR_KM = 5.0

    /** How much road before the planned stop is worth asking about. */
    const val WINDOW_KM = 40.0

    /**
     * No published figure to respect, so this is a self-imposed one. OCM's fair-use policy is a
     * human judgement — "at the discretion of the OCM administrator" — and the way to stay
     * inside a limit nobody states is to ask rarely.
     */
    fun quota(): RoutingQuota = RoutingQuota(dayLimit = 500, minuteLimit = 10)

    /**
     * One charger, with everything needed to show it honestly.
     *
     * [verifiedAt] and [dataProvider] are not decoration: CP-048 requires that a charger carries
     * its source and the age of its claim, because neither this app nor OCM knows whether the
     * unit is there today.
     */
    data class Charger(
        val name: String,
        val longitude: Double,
        val latitude: Double,
        val powerKw: Double?,
        val connectors: List<String>,
        val operator: String?,
        val dataProvider: String?,
        val licence: String?,
        val verifiedAt: String?,
        val operational: Boolean?,
    )

    /**
     * The query, built from a piece of route rather than from the route.
     *
     * @param window the geometry between the two distances a stop could fall between.
     * @param corridorKm how far either side of that line to look.
     */
    fun query(
        window: List<OrsDirections.Point>,
        corridorKm: Double = CORRIDOR_KM,
        maxResults: Int = MAX_RESULTS,
    ): Map<String, String> = linkedMapOf(
        "output" to "json",
        "polyline" to RouteGeometry.encodePolyline(window),
        "distance" to trim(corridorKm),
        "distanceunit" to "KM",
        "maxresults" to maxResults.toString(),
        // Expanded objects, minus the nulls: the provider name and the connector titles are the
        // attribution and the filter, and fetching IDs alone would mean a second request to
        // learn what they mean.
        "compact" to "false",
        "verbose" to "false",
    )

    /**
     * Every charger in the answer that an MG4 can use, or an empty list.
     *
     * Filtering happens here rather than in the query because OCM has no minimum-power parameter
     * and because a connector filter sent to the server turns a wrong ID into an empty answer
     * that looks like an empty road.
     */
    fun parse(json: String, minPowerKw: Double = 0.0): List<Charger> {
        val root = runCatching { JsonParser.parseString(json) as? JsonArray }.getOrNull()
            ?: return emptyList()
        // Per record, not per response. OCM leaves whole members explicitly null — an
        // unattributed site carries `"OperatorInfo": null`, an unknown status carries
        // `"StatusTypeID": null` — and Gson answers those with JsonNull, which throws on
        // every typed accessor. One such site used to discard every charger in the answer,
        // which reads on screen as an empty road.
        return root.mapNotNull { element ->
            runCatching { (element as? JsonObject)?.let { charger(it, minPowerKw) } }.getOrNull()
        }
    }

    private fun charger(poi: JsonObject, minPowerKw: Double): Charger? {
        val address = obj(poi, "AddressInfo") ?: return null
        val longitude = number(address, "Longitude") ?: return null
        val latitude = number(address, "Latitude") ?: return null
        if (!longitude.isFinite() || !latitude.isFinite()) return null
        if (longitude !in -180.0..180.0 || latitude !in -90.0..90.0) return null

        if (integer(poi, "StatusTypeID") in UNUSABLE_STATUS) return null

        val connections = array(poi, "Connections") ?: return null
        var bestPower: Double? = null
        val titles = LinkedHashSet<String>()
        for (element in connections) {
            val connection = element as? JsonObject ?: continue
            val typeId = integer(connection, "ConnectionTypeID") ?: continue
            if (typeId !in USABLE_CONNECTORS) continue
            if (integer(connection, "StatusTypeID") in UNUSABLE_STATUS) continue
            val power = number(connection, "PowerKW")
            if (power != null && power.isFinite() && (bestPower == null || power > bestPower)) {
                bestPower = power
            }
            obj(connection, "ConnectionType")?.let { string(it, "Title") }?.let { titles.add(it) }
        }
        if (titles.isEmpty()) return null
        val best = bestPower
        if (minPowerKw > 0.0 && (best == null || best < minPowerKw)) return null

        return Charger(
            name = string(address, "Title") ?: return null,
            longitude = longitude,
            latitude = latitude,
            powerKw = bestPower,
            connectors = titles.toList(),
            operator = obj(poi, "OperatorInfo")?.let { string(it, "Title") },
            dataProvider = obj(poi, "DataProvider")?.let { string(it, "Title") },
            licence = obj(poi, "DataProvider")?.let { provider ->
                obj(provider, "License")?.let { string(it, "Title") } ?: string(provider, "License")
            },
            // DateLastVerified is OCM's own multi-signal estimate of when this was last
            // confirmed; DateLastStatusUpdate is when anything about it moved. Either is a
            // better answer to "how old is this" than silence.
            verifiedAt = string(poi, "DateLastVerified") ?: string(poi, "DateLastStatusUpdate"),
            operational = obj(poi, "StatusType")
                ?.get("IsOperational")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        )
    }

    private fun string(owner: JsonObject, member: String): String? =
        owner.get(member)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    // A member OCM left null is JsonNull, not a missing key, and every typed Gson accessor
    // throws on it — `getAsJsonObject` casts and `asInt` refuses. Read through these instead.
    private fun obj(owner: JsonObject, member: String): JsonObject? = owner.get(member) as? JsonObject

    private fun array(owner: JsonObject, member: String): JsonArray? = owner.get(member) as? JsonArray

    private fun number(owner: JsonObject, member: String): Double? =
        runCatching { owner.get(member)?.takeIf { it.isJsonPrimitive }?.asDouble }.getOrNull()

    private fun integer(owner: JsonObject, member: String): Int? =
        runCatching { owner.get(member)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
