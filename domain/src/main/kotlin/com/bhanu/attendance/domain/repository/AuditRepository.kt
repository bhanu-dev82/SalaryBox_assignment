package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.AuditEvent
import com.bhanu.attendance.domain.model.Role
import kotlinx.coroutines.flow.Flow

/**
 * Append-only trail of privileged actions. A fresh pair of eyes should be able to answer
 * "who re-enrolled Ravi's face, and when?" without asking anyone.
 */
interface AuditRepository {
    fun observeRecent(limit: Int = 100): Flow<List<AuditEvent>>

    /** [detail] must never contain a PIN, a face descriptor, or any other secret. */
    suspend fun log(
        actorId: String,
        actorRole: Role,
        action: AuditAction,
        targetStaffId: String? = null,
        detail: String? = null,
    )
}
