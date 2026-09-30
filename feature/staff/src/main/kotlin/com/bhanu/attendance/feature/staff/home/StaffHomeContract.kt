package com.bhanu.attendance.feature.staff.home

import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.usecase.PunchAvailability
import com.bhanu.attendance.domain.usecase.PunchRules
import com.bhanu.attendance.domain.usecase.TodayPunchSummary
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class StaffHomeUiState(
    val staffName: String = "",
    val summary: TodayPunchSummary? = null,
    val isLoading: Boolean = true,
) {
    /** Blocked while loading, so the punch button is inert rather than briefly wrong. */
    val availability: PunchAvailability
        get() = summary?.availability ?: PunchAvailability.Blocked("Loading")

    val nextPunch: PunchType? get() = PunchRules.nextPunchType(availability)

    val punchInLabel: String? get() = summary?.punchIn?.let { "Punched in at ${it.occurredAt.displayTime()}" }

    val punchOutLabel: String? get() = summary?.punchOut?.let { "Punched out at ${it.occurredAt.displayTime()}" }
}

/** Human-readable time in the device's own zone and locale. */
fun java.time.Instant.displayTime(
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withZone(zone)
    .format(this)

fun java.time.Instant.displayDateTime(
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(zone)
    .format(this)

/** A row in the history list. */
data class HistoryItem(
    val id: String,
    val staffName: String,
    val punchType: PunchType,
    val occurredAt: java.time.Instant,
    val matchScore: Double,
    val geofenceLabel: String,
    val selfiePath: String,
) {
    val scorePercent: Int get() = (matchScore * 100).toInt().coerceIn(0, 100)

    /**
     * One spoken sentence for the whole row.
     *
     * `PunchType` is an enum, so `toString()` would read "PUNCH_IN" aloud. Mapping it to a
     * word is the difference between a usable screen and an absurd one.
     */
    val accessibilityLabel: String
        get() = "${if (punchType == PunchType.PUNCH_IN) "Punched in" else "Punched out"} " +
            "on $occurredAt, face match $scorePercent percent, $geofenceLabel"
}

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val scope: HistoryScope = HistoryScope.MINE,
)

enum class HistoryScope { MINE, ALL }
