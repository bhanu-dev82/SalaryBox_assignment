package com.bhanu.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bhanu.attendance.data.local.entity.AttendanceEntity
import com.bhanu.attendance.domain.model.PunchType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface AttendanceDao {

    @Query("SELECT * FROM attendance ORDER BY occurred_at DESC")
    fun observeAll(): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE staff_id = :staffId ORDER BY occurred_at DESC")
    fun observeForStaff(staffId: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE local_date = :date ORDER BY occurred_at DESC")
    fun observeForDate(date: LocalDate): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE staff_id = :staffId AND local_date = :date ORDER BY occurred_at ASC")
    suspend fun getForStaffAndDate(staffId: String, date: LocalDate): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE staff_id = :staffId ORDER BY occurred_at DESC LIMIT 1")
    suspend fun latestForStaff(staffId: String): AttendanceEntity?

    @Query("SELECT COUNT(*) FROM attendance WHERE staff_id = :staffId")
    suspend fun countForStaff(staffId: String): Int

    @Query("SELECT COUNT(*) FROM attendance WHERE staff_id = :staffId AND punch_type = :type")
    suspend fun countForStaffAndType(staffId: String, type: PunchType): Int

    /**
     * `ABORT` matters: the unique index on (staff_id, local_date, punch_type) turns a
     * duplicate punch into a constraint violation that the repository can translate into a
     * typed error. `REPLACE` or `IGNORE` would silently drop the second attempt, which is how
     * "I punched in twice and the app said it was fine" bugs happen.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceEntity)

    @Query("DELETE FROM attendance WHERE id = :id")
    suspend fun delete(id: String)
}
