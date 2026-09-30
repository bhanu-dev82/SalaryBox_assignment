package com.bhanu.attendance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.GeofenceState
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Role
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "staff",
    indices = [
        // Enforced by the database, not only in the repository: two concurrent "add staff"
        // taps must not be able to create the same employee id.
        Index(value = ["employee_id"], unique = true),
    ],
)
data class StaffEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "employee_id") val employeeId: String,
    val name: String,
    val role: Role,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "face_enrolled_at") val faceEnrolledAt: Instant?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "last_login_at") val lastLoginAt: Instant?,
)

/**
 * PIN hashes, held in a separate table.
 *
 * Deliberately not columns on `staff`: a separate table makes it obvious to a reviewer that
 * credentials are isolated, and means any future query over `staff` cannot accidentally
 * select the hash.
 */
@Entity(
    tableName = "credentials",
    foreignKeys = [
        ForeignKey(
            entity = StaffEntity::class,
            parentColumns = ["id"],
            childColumns = ["staff_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CredentialEntity(
    @PrimaryKey @ColumnInfo(name = "staff_id") val staffId: String,
    @ColumnInfo(name = "pin_hash") val pinHash: ByteArray,
    @ColumnInfo(name = "pin_salt") val pinSalt: ByteArray,
    @ColumnInfo(name = "iterations") val iterations: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
) {
    // ByteArray in a data class breaks the generated equals/hashCode, which Room relies on
    // for change detection. Compare content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return staffId == other.staffId &&
            pinHash.contentEquals(other.pinHash) &&
            pinSalt.contentEquals(other.pinSalt) &&
            iterations == other.iterations &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = staffId.hashCode()
        result = 31 * result + pinHash.contentHashCode()
        result = 31 * result + pinSalt.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/** Enrolled face template. Biometric data: on-device only, excluded from backups. */
@Entity(
    tableName = "face_templates",
    foreignKeys = [
        ForeignKey(
            entity = StaffEntity::class,
            parentColumns = ["id"],
            childColumns = ["staff_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FaceTemplateEntity(
    @PrimaryKey @ColumnInfo(name = "staff_id") val staffId: String,
    /** L2-normalised descriptor, little-endian float32. */
    @ColumnInfo(name = "descriptor") val descriptor: ByteArray,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "intra_mean") val intraClassSimilarity: Double,
    @ColumnInfo(name = "intra_min") val intraClassMinSimilarity: Double,
    @ColumnInfo(name = "model_version") val modelVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceTemplateEntity) return false
        return staffId == other.staffId &&
            descriptor.contentEquals(other.descriptor) &&
            sampleCount == other.sampleCount &&
            createdAt == other.createdAt &&
            intraClassSimilarity == other.intraClassSimilarity &&
            intraClassMinSimilarity == other.intraClassMinSimilarity &&
            modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = staffId.hashCode()
        result = 31 * result + descriptor.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + intraClassSimilarity.hashCode()
        result = 31 * result + intraClassMinSimilarity.hashCode()
        result = 31 * result + modelVersion
        return result
    }
}

@Entity(
    tableName = "attendance",
    foreignKeys = [
        ForeignKey(
            entity = StaffEntity::class,
            parentColumns = ["id"],
            childColumns = ["staff_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["staff_id", "local_date", "punch_type"], unique = true),
        Index(value = ["local_date"]),
        Index(value = ["staff_id", "occurred_at"]),
    ],
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "staff_id") val staffId: String,
    @ColumnInfo(name = "staff_name") val staffName: String,
    @ColumnInfo(name = "employee_id") val employeeId: String,
    @ColumnInfo(name = "punch_type") val punchType: PunchType,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "utc_offset_seconds") val utcOffsetSeconds: Int,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    @ColumnInfo(name = "selfie_path") val selfiePath: String,
    @ColumnInfo(name = "match_score") val matchScore: Double,
    @ColumnInfo(name = "matched_frames") val matchedFrames: Int,
    @ColumnInfo(name = "considered_frames") val consideredFrames: Int,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,
    @ColumnInfo(name = "provider") val provider: String?,
    @ColumnInfo(name = "geofence_state") val geofenceState: GeofenceState,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

@Entity(tableName = "audit_events", indices = [Index(value = ["at"])])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val at: Instant,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "actor_role") val actorRole: Role,
    val action: AuditAction,
    @ColumnInfo(name = "target_staff_id") val targetStaffId: String?,
    val detail: String?,
)

/**
 * Small key/value settings table.
 *
 * DataStore is used for the session, but geofence and face thresholds are modelled as rows so
 * they participate in the same transactional story as everything else. Both mechanisms are
 * used deliberately, for the two jobs each is actually good at.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String?,
)
