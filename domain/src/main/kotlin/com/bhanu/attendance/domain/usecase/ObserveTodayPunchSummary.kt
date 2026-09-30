package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.repository.AttendanceRepository
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.domain.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The employee home screen's single source of truth: today's records plus what may be done
 * next, recomputed automatically whenever any of the three inputs change.
 *
 * All three inputs are flows, so an admin enrolling the employee, or the admin toggling
 * punch-out, updates the employee's screen with no refresh and no sign-out.
 */
class ObserveTodayPunchSummaryUseCase @Inject constructor(
    private val attendanceRepository: AttendanceRepository,
    private val settingsRepository: SettingsRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val timeProvider: TimeProvider,
) {
    operator fun invoke(staffId: String): Flow<TodayPunchSummary> {
        val today = timeProvider.today()
        return combine(
            attendanceRepository.observeForStaff(staffId)
                .map { all -> all.filter { it.localDate == today } },
            settingsRepository.punchOutEnabled,
            faceTemplateRepository.observeEnrolled(staffId),
        ) { todayRecords, punchOutEnabled, isEnrolled ->
            PunchRules.evaluate(
                records = todayRecords,
                date = today,
                punchOutEnabled = punchOutEnabled,
                isFaceEnrolled = isEnrolled,
            )
        }
    }
}

/** Wraps the repository for ViewModels that only need the raw record list. */
class ObserveStaffAttendanceUseCase @Inject constructor(
    private val attendanceRepository: AttendanceRepository,
) {
    operator fun invoke(staffId: String): Flow<List<AttendanceRecord>> =
        attendanceRepository.observeForStaff(staffId)
}

/** Admin dashboard: everything recorded on a given local date. */
class ObserveAttendanceForDateUseCase @Inject constructor(
    private val attendanceRepository: AttendanceRepository,
) {
    operator fun invoke(date: java.time.LocalDate): Flow<List<AttendanceRecord>> =
        attendanceRepository.observeForDate(date)
}
