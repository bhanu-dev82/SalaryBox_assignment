package com.bhanu.attendance.data.repository

import androidx.room.withTransaction
import com.bhanu.attendance.data.local.AttendanceDatabase
import com.bhanu.attendance.data.local.dao.StaffDao
import com.bhanu.attendance.data.mapper.toDomain
import com.bhanu.attendance.data.mapper.toEntity
import com.bhanu.attendance.data.security.PinHasher
import com.bhanu.attendance.domain.model.Staff
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.time.TimeProvider
import com.bhanu.attendance.domain.validation.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StaffRepositoryImpl @Inject constructor(
    private val database: AttendanceDatabase,
    private val staffDao: StaffDao,
    private val pinHasher: PinHasher,
    private val timeProvider: TimeProvider,
) : StaffRepository {

    override fun observeAll(): Flow<List<Staff>> =
        staffDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeActive(): Flow<List<Staff>> =
        staffDao.observeActive().map { list -> list.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Staff?> =
        staffDao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: String): Staff? = staffDao.getById(id)?.toDomain()

    override suspend fun getByEmployeeId(employeeId: String): Staff? =
        staffDao.getByEmployeeId(Validators.normaliseEmployeeId(employeeId))?.toDomain()

    override suspend fun create(staff: Staff, pin: String): Outcome<Staff> {
        val normalised = Validators.normaliseEmployeeId(staff.employeeId)
        if (staffDao.getByEmployeeId(normalised) != null) {
            return Outcome.failure(AppError.DuplicateEmployeeId)
        }
        val pinHash = pinHasher.hash(pin)
        return runCatchingOutcome {
            // Transactional: a staff row without a credential is someone who can never sign
            // in, and that failure would only surface later, on their first login attempt.
            database.withTransaction {
                staffDao.insertStaffWithCredential(
                    staff.copy(employeeId = normalised).toEntity(),
                    pinHash.toEntity(staff.id, timeProvider.now()),
                )
            }
            staff.copy(employeeId = normalised)
        }
    }

    override suspend fun updateName(id: String, name: String): Outcome<Unit> =
        runCatchingOutcome { staffDao.updateName(id, Validators.normaliseName(name)) }

    override suspend fun setActive(id: String, active: Boolean): Outcome<Unit> =
        runCatchingOutcome { staffDao.setActive(id, active) }

    override suspend fun resetPin(staffId: String, newPin: String): Outcome<Unit> {
        // Reading first turns a bad staff id into a clear "not found" rather than a silent
        // no-op: REPLACE on a missing primary key would otherwise insert an orphan row.
        staffDao.getCredential(staffId) ?: return Outcome.failure(AppError.StaffNotFound(staffId))
        return runCatchingOutcome {
            database.withTransaction {
                staffDao.updateCredential(staffId, pinHasher.hash(newPin).toEntity(staffId, timeProvider.now()))
            }
        }
    }

    override suspend fun setPin(staffId: String, currentPin: String, newPin: String): Outcome<Unit> {
        val existing = staffDao.getCredential(staffId)
            ?: return Outcome.failure(AppError.StaffNotFound(staffId))
        if (!pinHasher.verify(currentPin, existing.toPinHash())) {
            return Outcome.failure(AppError.InvalidCredentials("current PIN"))
        }
        return runCatchingOutcome {
            database.withTransaction {
                staffDao.updateCredential(staffId, pinHasher.hash(newPin).toEntity(staffId, timeProvider.now()))
            }
        }
    }

    override suspend fun recordLogin(id: String, at: Instant) {
        runCatchingOutcome { staffDao.setLastLoginAt(id, at) }
    }

    override suspend fun countAll(): Int = staffDao.countAll()
}

/** Local import kept separate to avoid a naming clash with the generated `toPinHash`. */
private fun com.bhanu.attendance.data.local.entity.CredentialEntity.toPinHash() =
    com.bhanu.attendance.data.security.PinHash(pinHash, pinSalt, iterations)
