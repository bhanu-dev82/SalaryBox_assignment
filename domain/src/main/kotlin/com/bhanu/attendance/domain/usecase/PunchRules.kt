package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.PunchType
import java.time.LocalDate

/** What the employee is allowed to do right now, and why. */
sealed interface PunchAvailability {
    /** No punch recorded for today yet. */
    data object ReadyPunchIn : PunchAvailability

    /** Punched in, and punch-out is enabled. */
    data object ReadyPunchOut : PunchAvailability

    /** Nothing left to do for today. */
    data object Complete : PunchAvailability

    /**
     * Blocked for a reason that is not about the punch sequence — most often a missing face
     * enrolment, which is an admin-side problem the employee cannot fix.
     */
    data class Blocked(val reason: String) : PunchAvailability
}

data class TodayPunchSummary(
    val date: LocalDate,
    val punchIn: AttendanceRecord?,
    val punchOut: AttendanceRecord?,
    val availability: PunchAvailability,
    val punchOutEnabled: Boolean,
)

/**
 * The single source of truth for the punch-in/punch-out sequence.
 *
 * Deliberately a pure function over records rather than state held in a ViewModel. Every rule
 * below is then trivially testable, and there is exactly one implementation of "what may I do
 * next", shared by the UI, the use cases and the tests — which is how the app avoids the
 * classic bug where the button says "Punch out" but the repository rejects it.
 *
 * Flexibility lives in [punchOutEnabled]: the same rule set covers a single-punch-in day and a
 * full punch-in/punch-out day without a second code path.
 */
object PunchRules {

    fun evaluate(
        records: List<AttendanceRecord>,
        date: LocalDate,
        punchOutEnabled: Boolean,
        isFaceEnrolled: Boolean,
    ): TodayPunchSummary {
        // Filtered here rather than trusting the caller. The parameter used to be named
        // `recordsForDate` while the body accepted anything it was given, and a test caught a
        // caller relying on that. Since this object is the single source of truth for the
        // sequence, it should be correct even when handed a wider list than expected.
        val recordsForDate = records.filter { it.localDate == date }
        val punchIn = recordsForDate.firstOrNull { it.punchType == PunchType.PUNCH_IN }
        val punchOut = recordsForDate.firstOrNull { it.punchType == PunchType.PUNCH_OUT }

        val availability = when {
            !isFaceEnrolled -> PunchAvailability.Blocked(
                "Your face is not enrolled yet. Ask an admin to enrol it first."
            )
            punchIn == null && punchOut == null -> PunchAvailability.ReadyPunchIn
            punchIn != null && punchOut == null && punchOutEnabled -> PunchAvailability.ReadyPunchOut
            // Either punch-out is not required, or the shift is already finished.
            else -> PunchAvailability.Complete
        }

        return TodayPunchSummary(
            date = date,
            punchIn = punchIn,
            punchOut = punchOut,
            availability = availability,
            punchOutEnabled = punchOutEnabled,
        )
    }

    /** What the primary button should do, or null when there is nothing to offer. */
    fun nextPunchType(availability: PunchAvailability): PunchType? = when (availability) {
        PunchAvailability.ReadyPunchIn -> PunchType.PUNCH_IN
        PunchAvailability.ReadyPunchOut -> PunchType.PUNCH_OUT
        PunchAvailability.Complete, is PunchAvailability.Blocked -> null
    }
}
