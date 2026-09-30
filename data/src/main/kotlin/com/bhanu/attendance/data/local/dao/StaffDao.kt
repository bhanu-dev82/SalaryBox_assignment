package com.bhanu.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.bhanu.attendance.data.local.entity.CredentialEntity
import com.bhanu.attendance.data.local.entity.StaffEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffDao {

    @Query("SELECT * FROM staff ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff WHERE is_active = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff WHERE id = :id")
    fun observeById(id: String): Flow<StaffEntity?>

    @Query("SELECT * FROM staff WHERE id = :id")
    suspend fun getById(id: String): StaffEntity?

    @Query("SELECT * FROM staff WHERE employee_id = :employeeId COLLATE NOCASE")
    suspend fun getByEmployeeId(employeeId: String): StaffEntity?

    @Query("SELECT COUNT(*) FROM staff")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(staff: StaffEntity)

    @Update
    suspend fun update(staff: StaffEntity)

    @Query("UPDATE staff SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE staff SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("UPDATE staff SET face_enrolled_at = :at WHERE id = :id")
    suspend fun setFaceEnrolledAt(id: String, at: java.time.Instant?)

    @Query("UPDATE staff SET last_login_at = :at WHERE id = :id")
    suspend fun setLastLoginAt(id: String, at: java.time.Instant)

    // --- credentials ---

    @Query("SELECT * FROM credentials WHERE staff_id = :staffId")
    suspend fun getCredential(staffId: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredential(credential: CredentialEntity)

    /**
     * Staff row and credential are written together or not at all.
     *
     * Without this, a crash between the two inserts would leave a staff member who can never
     * sign in, and no error would be raised at the point it mattered.
     */
    @Transaction
    suspend fun insertStaffWithCredential(staff: StaffEntity, credential: CredentialEntity) {
        insert(staff)
        upsertCredential(credential)
    }

    @Transaction
    suspend fun updateCredential(staffId: String, credential: CredentialEntity) {
        upsertCredential(credential)
    }
}
