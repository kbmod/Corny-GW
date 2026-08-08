package com.kbmod.cornygw.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A point in a local east/north plane, metres from the frame origin. */
data class LocalPoint(val east: Double, val north: Double) {
    fun distanceTo(other: LocalPoint): Double = hypot(east - other.east, north - other.north)
}

/**
 * Flat-earth projection anchored at an origin.
 *
 * A survey spans a street, not a continent, so treating the neighbourhood as a
 * plane costs millimetres and saves a great deal of spherical trigonometry.
 */
class LocalFrame(val originLat: Double, val originLon: Double) {

    private val metresPerDegreeLat: Double
    private val metresPerDegreeLon: Double

    init {
        val latRad = Math.toRadians(originLat)
        // WGS84 arc lengths, series expansion. Accurate to well under a metre
        // per degree across the usable latitude range.
        metresPerDegreeLat =
            111132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad) - 0.0023 * cos(6 * latRad)
        metresPerDegreeLon =
            111412.84 * cos(latRad) - 93.5 * cos(3 * latRad) + 0.118 * cos(5 * latRad)
    }

    fun toLocal(lat: Double, lon: Double): LocalPoint = LocalPoint(
        east = (lon - originLon) * metresPerDegreeLon,
        north = (lat - originLat) * metresPerDegreeLat,
    )

    fun toLatLon(point: LocalPoint): Pair<Double, Double> = Pair(
        originLat + point.north / metresPerDegreeLat,
        originLon + point.east / metresPerDegreeLon,
    )
}

object Geo {

    /** Compass bearing in degrees (0 = north, clockwise) from origin to target. */
    fun bearingDegrees(from: LocalPoint, to: LocalPoint): Double {
        val degrees = Math.toDegrees(atan2(to.east - from.east, to.north - from.north))
        return (degrees + 360.0) % 360.0
    }

    private val COMPASS_POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compassPoint(bearingDegrees: Double): String {
        val normalized = (bearingDegrees % 360.0 + 360.0) % 360.0
        val index = ((normalized / 22.5) + 0.5).toInt() % 16
        return COMPASS_POINTS[index]
    }

    /** Smallest signed difference between two bearings, in (-180, 180]. */
    fun angleDelta(fromDegrees: Double, toDegrees: Double): Double {
        var delta = (toDegrees - fromDegrees) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta <= -180.0) delta += 360.0
        return delta
    }

    fun formatDistance(metres: Double): String = when {
        metres < 10.0 -> String.format("%.1f m", metres)
        metres < 1000.0 -> "${metres.roundToInt()} m"
        else -> String.format("%.2f km", metres / 1000.0)
    }

    fun formatLatLon(lat: Double, lon: Double): String =
        String.format("%.6f, %.6f", lat, lon)

    /**
     * How much of the circle around [centre] the observations cover, 0..1.
     *
     * This is the honest health check on a survey. Signal strength alone fixes
     * a *radius*; only walking around the target fixes an *angle*. Sixteen
     * samples taken from one spot on the pavement produce a confident-looking
     * fit that is free to slide anywhere along that radius, and this number is
     * what tells the user so.
     */
    fun angularCoverage(centre: LocalPoint, points: List<LocalPoint>): Double {
        if (points.isEmpty()) return 0.0
        val sectors = BooleanArray(8)
        for (point in points) {
            if (point.distanceTo(centre) < 1.0) continue
            val bearing = bearingDegrees(centre, point)
            sectors[((bearing / 45.0).toInt()) % 8] = true
        }
        return sectors.count { it } / 8.0
    }

    /** Root mean square, used for residual reporting. */
    fun rms(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { it * it } / values.size)
    }

    fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.sumOf { val d = it - mean; d * d } / values.size
    }

    fun approxEquals(a: Double, b: Double, tolerance: Double = 1e-9): Boolean =
        abs(a - b) <= tolerance
}
