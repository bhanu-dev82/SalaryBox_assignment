package com.bhanu.attendance.domain.model

import java.time.Instant
import java.time.LocalDate

enum class PunchType { PUNCH_IN, PUNCH_OUT }

/**
 * One punch event.
 *
 * [localDate] and [zoneId] are denormalised onto the record so that "punch-ins for a
 * given day" is a plain indexed equality query rather than a per-row timezone
 * conversion. A UTC range query would be wrong for anyone whose shift crosses
 * midnight in their own zone.
 */
data class AttendanceRecord(
    val id: String,
    val staffId: String,
    val staffName: String,
    val employeeId: String,
    val punchType: PunchType,
    val occurredAt: Instant,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val localDate: LocalDate,
    val selfiePath: String,
    val matchScore: Double,
    val matchedFrames: Int,
    val consideredFrames: Int,
    val location: GeoLocation?,
    val geofenceState: GeofenceState,
    val createdAt: Instant,
) {
    val withinGeofence: Boolean get() = geofenceState == GeofenceState.INSIDE
}
