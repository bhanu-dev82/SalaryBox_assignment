package com.bhanu.attendance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bhanu.attendance.data.local.entity.FaceTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceTemplateDao {

    @Query("SELECT * FROM face_templates WHERE staff_id = :staffId")
    suspend fun get(staffId: String): FaceTemplateEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM face_templates WHERE staff_id = :staffId)")
    fun observeEnrolled(staffId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM face_templates")
    suspend fun countEnrolled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: FaceTemplateEntity)

    @Query("DELETE FROM face_templates WHERE staff_id = :staffId")
    suspend fun delete(staffId: String)
}
