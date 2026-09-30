package com.bhanu.attendance.data.repository

import com.bhanu.attendance.data.local.dao.AuditDao
import com.bhanu.attendance.data.mapper.toDomain
import com.bhanu.attendance.data.mapper.toEntity
import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.AuditEvent
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.domain.time.IdGenerator
import com.bhanu.attendance.domain.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val auditDao: AuditDao,
    private val timeProvider: TimeProvider,
    private val idGenerator: IdGenerator,
) : AuditRepository {

    override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
        auditDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /**
     * Never throws.
     *
     * Auditing is a side concern and must never be the reason an operation fails — a staff
     * member's punch must still be recorded if the audit insert fails.
     */
    override suspend fun log(
        actorId: String,
        actorRole: Role,
        action: AuditAction,
        targetStaffId: String?,
        detail: String?,
    ) {
        runCatchingOutcome {
            auditDao.insert(
                AuditEvent(
                    id = idGenerator.newId(),
                    at = timeProvider.now(),
                    actorId = actorId,
                    actorRole = actorRole,
                    action = action,
                    targetStaffId = targetStaffId,
                    detail = detail?.take(MAX_DETAIL_LENGTH),
                ).toEntity()
            )
        }
    }

    private companion object {
        /** Keeps a runaway detail string from bloating the table. */
        const val MAX_DETAIL_LENGTH = 500
    }
}
