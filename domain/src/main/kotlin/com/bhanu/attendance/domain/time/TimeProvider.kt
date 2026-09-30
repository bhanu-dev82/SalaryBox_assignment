package com.bhanu.attendance.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Injected so that date-boundary logic is testable. A test can assert "a punch at 23:59 local
 * lands on the correct local date" without sleeping until midnight.
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** The local calendar date in the device's zone — what a person means by "today". */
    fun today(): LocalDate = LocalDate.ofInstant(now(), zone())
}

/**
 * What the punch rules are evaluated against.
 *
 * Note the deliberate choice that the *local* date is what matters. A night-shift worker
 * punching in at 23:40 and out at 06:00 must both count as one shift, and that only holds if
 * "today" is the worker's own calendar day, not UTC.
 */
object ShiftClock {
    fun localDateOf(instant: Instant, zone: ZoneId): LocalDate = LocalDate.ofInstant(instant, zone)
}
