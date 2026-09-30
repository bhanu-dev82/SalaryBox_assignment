package com.bhanu.attendance.domain.model

/** A circular work-site boundary. Radius in metres. */
data class Geofence(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val label: String,
) {
    init {
        require(radiusMeters > 0) { "radiusMeters must be positive, was $radiusMeters" }
    }

    companion object {
        val DEFAULT_RADIUS_METERS: Double = 200.0
    }
}

enum class GeofenceState {
    /** Within [Geofence.radiusMeters] of the site. */
    INSIDE,

    /** Outside the site boundary. */
    OUTSIDE,

    /** No geofence has been configured, so inside/outside is not meaningful. */
    NOT_CONFIGURED,

    /** A geofence exists but no location fix was available for this record. */
    LOCATION_UNAVAILABLE,
}
