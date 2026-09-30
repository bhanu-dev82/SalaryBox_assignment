package com.bhanu.attendance.data.repository

import com.bhanu.attendance.data.local.dao.StaffDao
import com.bhanu.attendance.data.mapper.toPinHash
import com.bhanu.attendance.data.security.PinHasher
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Session
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.repository.AuthRepository
import com.bhanu.attendance.domain.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-in against locally stored credential hashes.
 *
 * The admin account is a single seeded record rather than a hardcoded branch, so admins and
 * staff are authenticated by exactly the same code path and the same hashing. A hardcoded
 * admin PIN comparison would be a different, weaker thing that happens to sit next to it.
 *
 * Timing note: a failed PIN still performs a PBKDF2 verification against a dummy hash, so an
 * observer cannot distinguish "no such employee" from "wrong PIN" by response time.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val staffDao: StaffDao,
    private val sessionStore: SessionStore,
    private val pinHasher: PinHasher,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : AuthRepository {

    override val session: Flow<Session?> = sessionStore.session

    override suspend fun currentSession(): Session? = sessionStore.current()

    override suspend fun signInAdmin(pin: String): Outcome<Session> {
        val admin = staffDao.getById(ADMIN_ID)
        val credential = staffDao.getCredential(ADMIN_ID)
        if (admin == null || credential == null) {
            logger.e(TAG, "Admin record is missing; the database was not seeded")
            return Outcome.failure(AppError.Unexpected("Admin account is not set up", null))
        }
        if (!pinHasher.verify(pin, credential.toPinHash())) {
            return Outcome.failure(AppError.InvalidCredentials("admin PIN"))
        }
        return issueSession(userId = admin.id, role = Role.ADMIN, staffId = null)
    }

    override suspend fun signInStaff(employeeId: String, pin: String): Outcome<Session> {
        val staff = staffDao.getByEmployeeId(employeeId)
        if (staff == null) {
            // Burn comparable time so a missing employee is not detectable by latency.
            pinHasher.verify(pin, DUMMY_HASH)
            return Outcome.failure(AppError.InvalidCredentials("employee ID or PIN"))
        }
        if (!staff.isActive) {
            pinHasher.verify(pin, DUMMY_HASH)
            return Outcome.failure(AppError.StaffInactive(staff.name))
        }
        val credential = staffDao.getCredential(staff.id)
        if (credential == null || !pinHasher.verify(pin, credential.toPinHash())) {
            return Outcome.failure(AppError.InvalidCredentials("employee ID or PIN"))
        }
        return issueSession(userId = staff.id, role = Role.STAFF, staffId = staff.id)
    }

    override suspend fun signOut() {
        sessionStore.clear()
    }

    private suspend fun issueSession(userId: String, role: Role, staffId: String?): Outcome<Session> {
        val now = timeProvider.now()
        val session = Session(
            userId = userId,
            role = role,
            staffId = staffId,
            issuedAt = now,
            expiresAt = now.plus(Session.DEFAULT_LIFETIME),
        )
        sessionStore.save(session)
        return Outcome.success(session)
    }

    companion object {
        const val ADMIN_ID = "admin"
        private const val TAG = "AuthRepository"

        /** Only the shape matters; it is never expected to verify against a real PIN. */
        private val DUMMY_HASH = com.bhanu.attendance.data.security.PinHash(
            hash = ByteArray(32),
            salt = ByteArray(16),
            iterations = 120_000,
        )
    }
}
