package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.Staff
import com.bhanu.attendance.domain.outcome.Outcome
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface StaffRepository {
    fun observeAll(): Flow<List<Staff>>
    fun observeActive(): Flow<List<Staff>>
    fun observeById(id: String): Flow<Staff?>

    suspend fun getById(id: String): Staff?

    /** Looked up by the normalised (trimmed, upper-cased) employee id. */
    suspend fun getByEmployeeId(employeeId: String): Staff?

    /** Fails with [com.bhanu.attendance.domain.outcome.AppError.DuplicateEmployeeId] on collision. */
    suspend fun create(staff: Staff, pin: String): Outcome<Staff>

    suspend fun updateName(id: String, name: String): Outcome<Unit>
    suspend fun setActive(id: String, active: Boolean): Outcome<Unit>

    /** Admin-initiated reset. Does not require the old PIN. */
    suspend fun resetPin(staffId: String, newPin: String): Outcome<Unit>

    /** Self-service change; requires the current PIN. */
    suspend fun setPin(staffId: String, currentPin: String, newPin: String): Outcome<Unit>

    suspend fun recordLogin(id: String, at: Instant)
    suspend fun countAll(): Int
}
