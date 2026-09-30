package com.bhanu.attendance.data.local.converters

import androidx.room.TypeConverter
import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.GeofenceState
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Role
import java.time.Instant
import java.time.LocalDate

/**
 * Conversions for the domain types stored as columns.
 *
 * Instants and dates are persisted as their natural textual/primitive forms rather than a
 * single opaque blob, so the database stays queryable with plain SQL — which is what makes
 * "punch-ins on this local date" an indexed equality lookup.
 */
object RoomConverters {

    @TypeConverter
    @JvmStatic
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    @JvmStatic
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    @JvmStatic
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    @JvmStatic
    fun stringToLocalDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun roleToString(value: Role?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToRole(value: String?): Role? = value?.let { runCatching { Role.valueOf(it) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun punchTypeToString(value: PunchType?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToPunchType(value: String?): PunchType? =
        value?.let { runCatching { PunchType.valueOf(it) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun geofenceStateToString(value: GeofenceState?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToGeofenceState(value: String?): GeofenceState? =
        value?.let { runCatching { GeofenceState.valueOf(it) }.getOrNull() }

    @TypeConverter
    @JvmStatic
    fun auditActionToString(value: AuditAction?): String? = value?.name

    @TypeConverter
    @JvmStatic
    fun stringToAuditAction(value: String?): AuditAction? =
        value?.let { runCatching { AuditAction.valueOf(it) }.getOrNull() }
}
