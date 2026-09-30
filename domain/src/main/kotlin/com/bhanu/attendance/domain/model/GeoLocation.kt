package com.bhanu.attendance.domain.model

import java.time.Instant

/**
 * A location fix captured at the moment attendance was marked.
 *
 * [accuracyMeters] is nullable because a coarse fix is still worth recording: a location
 * with unknown accuracy is more honest than dropping the field entirely.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String?,
    val capturedAt: Instant,
)
