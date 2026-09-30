package com.bhanu.attendance.data.mapper

import com.bhanu.attendance.data.local.entity.AttendanceEntity
import com.bhanu.attendance.data.local.entity.AuditEventEntity
import com.bhanu.attendance.data.local.entity.CredentialEntity
import com.bhanu.attendance.data.local.entity.FaceTemplateEntity
import com.bhanu.attendance.data.local.entity.StaffEntity
import com.bhanu.attendance.data.face.toByteBuffer
import com.bhanu.attendance.data.face.toLittleEndianFloats
import com.bhanu.attendance.data.security.PinHash
import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.AuditEvent
import com.bhanu.attendance.domain.model.FaceTemplate
import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.Staff
import java.nio.ByteBuffer
import java.time.Instant

/*
 * Entity <-> domain mapping.
 *
 * Kept as explicit functions rather than Room `@TypeConverter`s on the domain classes, so the
 * domain layer stays free of persistence annotations and the storage format can change without
 * touching `:domain`.
 */

fun StaffEntity.toDomain(): Staff = Staff(
    id = id,
    employeeId = employeeId,
    name = name,
    role = role,
    isActive = isActive,
    faceEnrolledAt = faceEnrolledAt,
    createdAt = createdAt,
    lastLoginAt = lastLoginAt,
)

fun Staff.toEntity(): StaffEntity = StaffEntity(
    id = id,
    employeeId = employeeId,
    name = name,
    role = role,
    isActive = isActive,
    faceEnrolledAt = faceEnrolledAt,
    createdAt = createdAt,
    lastLoginAt = lastLoginAt,
)

fun CredentialEntity.toPinHash(): PinHash = PinHash(
    hash = pinHash,
    salt = pinSalt,
    iterations = iterations,
)

fun PinHash.toEntity(staffId: String, updatedAt: Instant): CredentialEntity = CredentialEntity(
    staffId = staffId,
    pinHash = hash,
    pinSalt = salt,
    iterations = iterations,
    updatedAt = updatedAt,
)

fun FaceTemplateEntity.toDomain(): FaceTemplate = FaceTemplate(
    staffId = staffId,
    descriptor = ByteBuffer.wrap(descriptor).toLittleEndianFloats(descriptor.size / 4),
    sampleCount = sampleCount,
    createdAt = createdAt,
    intraClassSimilarity = intraClassSimilarity,
    intraClassMinSimilarity = intraClassMinSimilarity,
    modelVersion = modelVersion,
)

fun FaceTemplate.toEntity(): FaceTemplateEntity = FaceTemplateEntity(
    staffId = staffId,
    descriptor = descriptor.toByteBuffer().let { buffer: ByteBuffer ->
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    },
    sampleCount = sampleCount,
    createdAt = createdAt,
    intraClassSimilarity = intraClassSimilarity,
    intraClassMinSimilarity = intraClassMinSimilarity,
    modelVersion = modelVersion,
)

fun AttendanceEntity.toDomain(): AttendanceRecord = AttendanceRecord(
    id = id,
    staffId = staffId,
    staffName = staffName,
    employeeId = employeeId,
    punchType = punchType,
    occurredAt = occurredAt,
    zoneId = zoneId,
    utcOffsetSeconds = utcOffsetSeconds,
    localDate = localDate,
    selfiePath = selfiePath,
    matchScore = matchScore,
    matchedFrames = matchedFrames,
    consideredFrames = consideredFrames,
    location = toGeoLocation(),
    geofenceState = geofenceState,
    createdAt = createdAt,
)

/** Null latitude means no fix was captured; that is distinct from a fix at 0,0. */
private fun AttendanceEntity.toGeoLocation(): GeoLocation? {
    if (latitude == null || longitude == null) return null
    return GeoLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        provider = provider,
        capturedAt = occurredAt,
    )
}

fun AttendanceRecord.toEntity(): AttendanceEntity = AttendanceEntity(
    id = id,
    staffId = staffId,
    staffName = staffName,
    employeeId = employeeId,
    punchType = punchType,
    occurredAt = occurredAt,
    zoneId = zoneId,
    utcOffsetSeconds = utcOffsetSeconds,
    localDate = localDate,
    selfiePath = selfiePath,
    matchScore = matchScore,
    matchedFrames = matchedFrames,
    consideredFrames = consideredFrames,
    latitude = location?.latitude,
    longitude = location?.longitude,
    accuracyMeters = location?.accuracyMeters,
    provider = location?.provider,
    geofenceState = geofenceState,
    createdAt = createdAt,
)

fun AuditEventEntity.toDomain(): AuditEvent = AuditEvent(
    id = id,
    at = at,
    actorId = actorId,
    actorRole = actorRole,
    action = action,
    targetStaffId = targetStaffId,
    detail = detail,
)

fun AuditEvent.toEntity(): AuditEventEntity = AuditEventEntity(
    id = id,
    at = at,
    actorId = actorId,
    actorRole = actorRole,
    action = action,
    targetStaffId = targetStaffId,
    detail = detail,
)
