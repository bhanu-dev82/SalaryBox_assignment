package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.Session
import com.bhanu.attendance.domain.outcome.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Session lifecycle.
 *
 * [session] is backed by persistent storage, not memory, so a process death or an activity
 * recreation does not sign the user out. A signed-out-out-of-nowhere app is a support ticket.
 */
interface AuthRepository {
    /** Emits the current session, or null when signed out. Survives process death. */
    val session: Flow<Session?>

    suspend fun currentSession(): Session?

    suspend fun signInAdmin(pin: String): Outcome<Session>

    /** Staff sign in with the employee ID they were issued plus their PIN. */
    suspend fun signInStaff(employeeId: String, pin: String): Outcome<Session>

    suspend fun signOut()
}
