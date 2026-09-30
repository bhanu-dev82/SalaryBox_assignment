package com.bhanu.attendance.domain.model

import java.time.Duration
import java.time.Instant

data class Session(
    val userId: String,
    val role: Role,
    /** Null for an admin, who is not tied to a staff record. */
    val staffId: String?,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = now.isBefore(expiresAt)

    fun remainingAt(now: Instant): Duration =
        Duration.between(now, expiresAt).takeIf { !it.isNegative } ?: Duration.ZERO

    companion object {
        /** Long enough for a shift on a single handset, short enough to matter if stolen. */
        val DEFAULT_LIFETIME: Duration = Duration.ofHours(12)
    }
}
