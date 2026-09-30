package com.bhanu.attendance.data.repository

import com.bhanu.attendance.data.local.dao.AttendanceDao
import com.bhanu.attendance.data.mapper.toDomain
import com.bhanu.attendance.data.mapper.toEntity
import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    private val attendanceDao: AttendanceDao,
) : AttendanceRepository {

    override fun observeAll(): Flow<List<AttendanceRecord>> =
        attendanceDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeForStaff(staffId: String): Flow<List<AttendanceRecord>> =
        attendanceDao.observeForStaff(staffId).map { list -> list.map { it.toDomain() } }

    override fun observeForDate(date: LocalDate): Flow<List<AttendanceRecord>> =
        attendanceDao.observeForDate(date).map { list -> list.map { it.toDomain() } }

    override suspend fun getForStaffAndDate(staffId: String, date: LocalDate): List<AttendanceRecord> =
        attendanceDao.getForStaffAndDate(staffId, date).map { it.toDomain() }

    /**
     * Inserts the punch.
     *
     * The unique index on (staff_id, local_date, punch_type) is the real guarantee against a
     * double punch. The check below exists only to produce a specific, user-facing message;
     * even if two calls race past it, the index rejects the loser and that is translated into
     * the same typed error.
     */
    override suspend fun record(record: AttendanceRecord): Outcome<AttendanceRecord> {
        val existing = attendanceDao.getForStaffAndDate(record.staffId, record.localDate)
        if (existing.any { it.punchType == record.punchType }) {
            return Outcome.failure(record.punchType.toDuplicateError())
        }
        return try {
            attendanceDao.insert(record.toEntity())
            Outcome.success(record)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // The unique index on (staff_id, local_date, punch_type) is the real guarantee
            // against a double punch; this translates the resulting constraint violation into
            // the same typed error the pre-check above would have produced.
            if (t.isConstraintViolation()) {
                Outcome.failure(record.punchType.toDuplicateError())
            } else {
                Outcome.failure(AppError.Unexpected("Could not save the punch", t))
            }
        }
    }

    override suspend fun latestForStaff(staffId: String): AttendanceRecord? =
        attendanceDao.latestForStaff(staffId)?.toDomain()

    override suspend fun countForStaff(staffId: String): Int = attendanceDao.countForStaff(staffId)
}

/**
 * Room wraps the SQLite exception, so match on the type name rather than importing an
 * implementation-specific class. Doing this keeps the translation robust across the
 * androidx.sqlite versions Room uses.
 */
private fun Throwable.isConstraintViolation(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.javaClass.simpleName.contains("ConstraintException")) return true
        current = current.cause
    }
    return false
}

private fun com.bhanu.attendance.domain.model.PunchType.toDuplicateError(): AppError =
    if (this == com.bhanu.attendance.domain.model.PunchType.PUNCH_IN) {
        AppError.AlreadyPunchedIn
    } else {
        AppError.AlreadyPunchedOut
    }
