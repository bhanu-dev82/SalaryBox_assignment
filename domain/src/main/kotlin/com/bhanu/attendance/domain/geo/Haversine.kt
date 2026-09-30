package com.bhanu.attendance.domain.geo

import com.bhanu.attendance.domain.model.GeoLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance on the WGS-84 mean sphere.
 *
 * Accurate to roughly 0.5%, which is orders of magnitude better than a handheld GPS fix is
 * precise, so it is more than sufficient for geofencing.
 */
object Haversine {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun distanceMeters(from: GeoLocation, to: GeoLocation): Double =
        distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
}
