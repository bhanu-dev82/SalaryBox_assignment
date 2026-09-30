package com.bhanu.attendance.domain.model

import java.time.Instant

enum class AuditAction {
    SESSION_STARTED, SESSION_ENDED, PIN_CHANGED, PIN_RESET,
    STAFF_CREATED, STAFF_UPDATED, STAFF_DEACTIVATED,
    FACE_ENROLLED, FACE_REENROLLED, FACE_ENROLMENT_FAILED,
    ATTENDANCE_MARKED, ATTENDANCE_REJECTED,
    GEOFENCE_UPDATED, THRESHOLD_UPDATED,
}

/**
 * An append-only audit trail of privileged actions.
 *
 * [detail] is free text for humans and must never contain PINs, face descriptors or
 * other secrets.
 */
data class AuditEvent(
    val id: String,
    val at: Instant,
    val actorId: String,
    val actorRole: Role,
    val action: AuditAction,
    val targetStaffId: String?,
    val detail: String?,
)
