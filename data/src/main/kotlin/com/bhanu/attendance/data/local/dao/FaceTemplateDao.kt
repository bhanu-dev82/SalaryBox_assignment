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

    @Query("SELECT EXISTS(SELECT 1 FROM face_templates WHERE staff_id = :staffId AND length(descriptor) > 0)")
    fun observeEnrolled(staffId: String): Flow<Boolean>

    /** Rows written by the empty-blob bug: the enrolment was recorded, but no face was stored. */
    @Query("SELECT staff_id FROM face_templates WHERE length(descriptor) = 0")
    suspend fun staffIdsWithEmptyDescriptor(): List<String>

    @Query("SELECT COUNT(*) FROM face_templates")
    suspend fun countEnrolled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: FaceTemplateEntity)

    @Query("DELETE FROM face_templates WHERE staff_id = :staffId")
    suspend fun delete(staffId: String)
}
