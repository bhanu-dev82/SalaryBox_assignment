package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.FaceTemplate
import kotlinx.coroutines.flow.Flow

/**
 * Storage for enrolled face templates.
 *
 * Biometric data: on-device only, in its own table, excluded from backups. See
 * docs/DECISIONS.md.
 */
interface FaceTemplateRepository {
    suspend fun get(staffId: String): FaceTemplate?
    suspend fun upsert(template: FaceTemplate)

    /** Clears the template, e.g. on re-enrolment failure or staff deactivation. */
    suspend fun delete(staffId: String)

    suspend fun countEnrolled(): Int

    /**
     * Reactive enrolment state, so the employee's home screen unlocks the moment an admin
     * enrols them, without the user having to sign out and back in.
     */
    fun observeEnrolled(staffId: String): Flow<Boolean>
}
