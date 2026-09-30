package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.GeofenceState
import com.bhanu.attendance.domain.model.PunchType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PunchRulesTest {

    private val today = LocalDate.of(2026, 9, 30)

    private fun record(
        type: PunchType,
        date: LocalDate = today,
        at: Instant = Instant.parse("2026-09-30T09:00:00Z"),
    ) = AttendanceRecord(
        id = "${type}-$date",
        staffId = "s1",
        staffName = "Ravi Kumar",
        employeeId = "EMP001",
        punchType = type,
        occurredAt = at,
        zoneId = "Asia/Kolkata",
        utcOffsetSeconds = 19800,
        localDate = date,
        selfiePath = "/data/selfies/$date.jpg",
        matchScore = 0.998,
        matchedFrames = 4,
        consideredFrames = 5,
        location = null,
        geofenceState = GeofenceState.NOT_CONFIGURED,
        createdAt = at,
    )

    @Test
    fun `no records and punch-out enabled offers punch-in`() {
        val summary = PunchRules.evaluate(emptyList(), today, punchOutEnabled = true, isFaceEnrolled = true)
        assertThat(summary.availability).isEqualTo(PunchAvailability.ReadyPunchIn)
        assertThat(PunchRules.nextPunchType(summary.availability)).isEqualTo(PunchType.PUNCH_IN)
    }

    @Test
    fun `punch-in recorded with punch-out enabled offers punch-out`() {
        val summary = PunchRules.evaluate(
            listOf(record(PunchType.PUNCH_IN)), today, punchOutEnabled = true, isFaceEnrolled = true
        )
        assertThat(summary.availability).isEqualTo(PunchAvailability.ReadyPunchOut)
        assertThat(PunchRules.nextPunchType(summary.availability)).isEqualTo(PunchType.PUNCH_OUT)
    }

    @Test
    fun `punch-in recorded with punch-out disabled completes the shift`() {
        // This is the "flexibility by rule" case: the same rule set, one setting, a different
        // day shape. Nothing here is a special-cased code path.
        val summary = PunchRules.evaluate(
            listOf(record(PunchType.PUNCH_IN)), today, punchOutEnabled = false, isFaceEnrolled = true
        )
        assertThat(summary.availability).isEqualTo(PunchAvailability.Complete)
        assertThat(PunchRules.nextPunchType(summary.availability)).isNull()
    }

    @Test
    fun `both punches recorded is complete`() {
        val summary = PunchRules.evaluate(
            listOf(record(PunchType.PUNCH_IN), record(PunchType.PUNCH_OUT)),
            today, punchOutEnabled = true, isFaceEnrolled = true,
        )
        assertThat(summary.availability).isEqualTo(PunchAvailability.Complete)
    }

    @Test
    fun `an unenrolled face blocks punching even with no records`() {
        val summary = PunchRules.evaluate(emptyList(), today, punchOutEnabled = true, isFaceEnrolled = false)
        assertThat(summary.availability).isInstanceOf(PunchAvailability.Blocked::class.java)
        assertThat(PunchRules.nextPunchType(summary.availability)).isNull()
    }

    @Test
    fun `the block message names the fix`() {
        val summary = PunchRules.evaluate(emptyList(), today, punchOutEnabled = true, isFaceEnrolled = false)
        val reason = (summary.availability as PunchAvailability.Blocked).reason
        assertThat(reason).contains("not enrolled")
    }

    @Test
    fun `records for other dates are ignored`() {
        val yesterday = today.minusDays(1)
        val summary = PunchRules.evaluate(
            listOf(record(PunchType.PUNCH_IN, date = yesterday), record(PunchType.PUNCH_OUT, date = yesterday)),
            today, punchOutEnabled = true, isFaceEnrolled = true,
        )
        assertThat(summary.availability).isEqualTo(PunchAvailability.ReadyPunchIn)
    }

    @Test
    fun `summary exposes the resolved records`() {
        val in_ = record(PunchType.PUNCH_IN)
        val out = record(PunchType.PUNCH_OUT)
        val summary = PunchRules.evaluate(listOf(in_, out), today, punchOutEnabled = true, isFaceEnrolled = true)
        assertThat(summary.punchIn).isEqualTo(in_)
        assertThat(summary.punchOut).isEqualTo(out)
    }
}
