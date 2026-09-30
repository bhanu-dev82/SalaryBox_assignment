package com.bhanu.attendance.domain.model

import java.time.Instant

/**
 * A staff member provisioned by an admin.
 *
 * Identity is [employeeId], not [id]: employees know their employee id and use it as
 * their username, whereas [id] is an opaque internal surrogate key used by the database.
 */
data class Staff(
    val id: String,
    val employeeId: String,
    val name: String,
    val role: Role,
    val isActive: Boolean,
    val faceEnrolledAt: Instant?,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
) {
    val isFaceEnrolled: Boolean get() = faceEnrolledAt != null
}
