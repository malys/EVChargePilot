package com.evsuite.chargepilot.route

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measuring along a route, and encoding a piece of one for someone else to read.
 *
 * CP-048 asks a charger service what is *on the way*, which needs three things the route itself
 * does not carry: how far along each point is, which stretch of it is worth asking about, and
 * how far along a charger sits once one comes back. All three are arithmetic over the CP-047
 * geometry, so all three live here where a JVM test can reach them and nothing Android is
 * involved.
 *
 * Distances are great-circle. Roads are not straight lines and this does not pretend they are —
 * it sums the segments the router returned, so the total is the road's length rather than the
 * crow's.
 */
object RouteGeometry {

    private const val EARTH_RADIUS_KM = 6371.0088

    /** How far along the route each point sits, in kilometres, starting at zero. */
    fun cumulativeKm(points: List<OrsDirections.Point>): DoubleArray {
        val out = DoubleArray(points.size)
        for (index in 1 until points.size) {
            out[index] = out[index - 1] + distanceKm(points[index - 1], points[index])
        }
        return out
    }

    /**
     * The stretch between two distances along the route.
     *
     * This is the privacy boundary of a charger query: the service is asked about the piece of
     * road where a stop could happen, not about the trip. An empty window yields an empty list
     * rather than the whole route, because a query that quietly widens is the failure this is
     * here to prevent.
     */
    fun window(
        points: List<OrsDirections.Point>,
        fromKm: Double,
        toKm: Double,
    ): List<OrsDirections.Point> {
        if (points.size < 2 || !(toKm > fromKm)) return emptyList()
        val cumulative = cumulativeKm(points)
        return points.filterIndexed { index, _ ->
            cumulative[index] >= fromKm && cumulative[index] <= toKm
        }
    }

    /**
     * How far along the route a point sits, measured to the nearest route vertex, or null when
     * that vertex is further away than [maxOffRouteKm].
     *
     * A charger 8 km off the road is not on this route however close it looks on a map, and
     * calling it "at kilometre 210" would place a stop the driver cannot make.
     */
    fun distanceAlongKm(
        points: List<OrsDirections.Point>,
        longitude: Double,
        latitude: Double,
        maxOffRouteKm: Double,
    ): Double? {
        if (points.isEmpty()) return null
        val cumulative = cumulativeKm(points)
        var bestKm = Double.MAX_VALUE
        var bestAlong = 0.0
        points.forEachIndexed { index, point ->
            val offset = distanceKm(point.longitude, point.latitude, longitude, latitude)
            if (offset < bestKm) {
                bestKm = offset
                bestAlong = cumulative[index]
            }
        }
        return if (bestKm <= maxOffRouteKm) bestAlong else null
    }

    /**
     * The Google encoded polyline the charger service expects, at the usual precision of five
     * decimal places — about a metre, far finer than a corridor query needs.
     *
     * Written here rather than pulled in: it is twenty lines, and a dependency added for twenty
     * lines is a dependency to update forever.
     */
    fun encodePolyline(points: List<OrsDirections.Point>): String {
        val out = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L
        for (point in points) {
            val lat = Math.round(point.latitude * 1e5)
            val lon = Math.round(point.longitude * 1e5)
            encodeValue(lat - previousLat, out)
            encodeValue(lon - previousLon, out)
            previousLat = lat
            previousLon = lon
        }
        return out.toString()
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        var shifted = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (shifted >= 0x20) {
            out.append(((0x20 or (shifted and 0x1f).toInt()) + 63).toChar())
            shifted = shifted shr 5
        }
        out.append((shifted.toInt() + 63).toChar())
    }

    fun distanceKm(from: OrsDirections.Point, to: OrsDirections.Point): Double =
        distanceKm(from.longitude, from.latitude, to.longitude, to.latitude)

    /** Haversine, which stays accurate for the short segments a route is made of. */
    fun distanceKm(
        fromLon: Double,
        fromLat: Double,
        toLon: Double,
        toLat: Double,
    ): Double {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(abs(a))))
    }
}
