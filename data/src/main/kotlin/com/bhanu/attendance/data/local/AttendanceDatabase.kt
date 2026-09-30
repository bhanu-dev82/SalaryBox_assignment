package com.bhanu.attendance.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bhanu.attendance.data.local.converters.RoomConverters
import com.bhanu.attendance.data.local.dao.AttendanceDao
import com.bhanu.attendance.data.local.dao.AuditDao
import com.bhanu.attendance.data.local.dao.FaceTemplateDao
import com.bhanu.attendance.data.local.dao.SettingsDao
import com.bhanu.attendance.data.local.dao.StaffDao
import com.bhanu.attendance.data.local.entity.AttendanceEntity
import com.bhanu.attendance.data.local.entity.AuditEventEntity
import com.bhanu.attendance.data.local.entity.CredentialEntity
import com.bhanu.attendance.data.local.entity.FaceTemplateEntity
import com.bhanu.attendance.data.local.entity.SettingEntity
import com.bhanu.attendance.data.local.entity.StaffEntity

@Database(
    entities = [
        StaffEntity::class,
        CredentialEntity::class,
        FaceTemplateEntity::class,
        AttendanceEntity::class,
        AuditEventEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun staffDao(): StaffDao
    abstract fun faceTemplateDao(): FaceTemplateDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun auditDao(): AuditDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val NAME = "bhanu-attendance.db"

        /**
         * Schemas are exported so migrations can be written and tested against the real
         * previous version, rather than being guessed at release time.
         */
        const val SCHEMA_DIR = "schemas"
    }
}
