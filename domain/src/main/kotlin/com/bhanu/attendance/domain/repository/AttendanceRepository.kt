package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.outcome.Outcome
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface AttendanceRepository {
    fun observeAll(): Flow<List<AttendanceRecord>>
    fun observeForStaff(staffId: String): Flow<List<AttendanceRecord>>
    fun observeForDate(date: LocalDate): Flow<List<AttendanceRecord>>

    suspend fun getForStaffAndDate(staffId: String, date: LocalDate): List<AttendanceRecord>

    /**
     * Inserts a punch, enforcing "at most one punch-in per staff per local date" atomically
     * inside a transaction. Fails with [com.bhanu.attendance.domain.outcome.AppError.AlreadyPunchedIn]
     * rather than relying on the UI to have checked first — the database is the arbiter, so two
     * rapid taps cannot produce two punch-ins.
     */
    suspend fun record(record: AttendanceRecord): Outcome<AttendanceRecord>

    suspend fun latestForStaff(staffId: String): AttendanceRecord?
    suspend fun countForStaff(staffId: String): Int
}
