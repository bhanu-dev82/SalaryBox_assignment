package com.bhanu.attendance.domain.geo

import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.Geofence
import com.bhanu.attendance.domain.model.GeofenceState
import kotlin.math.roundToInt

data class GeofenceResult(
    val state: GeofenceState,
    val distanceMeters: Double?,
)

/**
 * Classifies a location fix against the configured work-site boundary.
 *
 * Location is advisory, never blocking: the attendance record is always saved. Telling the
 * employee — and the admin reviewing the record days later — that they were outside the
 * boundary is far more useful than refusing to let someone work because their GPS was poor.
 */
object GeofenceEvaluator {

    fun evaluate(location: GeoLocation?, geofence: Geofence?): GeofenceResult {
        if (geofence == null) return GeofenceResult(GeofenceState.NOT_CONFIGURED, null)
        if (location == null) return GeofenceResult(GeofenceState.LOCATION_UNAVAILABLE, null)

        val distance = Haversine.distanceMeters(
            location.latitude, location.longitude,
            geofence.latitude, geofence.longitude,
        )
        val state =
            if (distance <= geofence.radiusMeters) GeofenceState.INSIDE else GeofenceState.OUTSIDE
        return GeofenceResult(state, distance)
    }

    /** Human-readable distance, e.g. `12 m`, `340 m`, `1.4 km`. */
    fun formatDistance(meters: Double?): String = when {
        meters == null -> "—"
        meters.isNaN() -> "—"
        meters < 1000 -> "${meters.roundToInt()} m"
        else -> String.format("%.1f km", meters / 1000.0)
    }
}
