package com.bhanu.attendance.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the session in DataStore.
 *
 * DataStore rather than SharedPreferences: writes are transactional and suspend-based, so
 * there is no `apply()` fire-and-forget that can lose a session, and reads are a `Flow` so
 * the UI updates on sign-in without any manual refresh.
 *
 * Persisting rather than holding in memory is a deliberate choice. The public reviews of the
 * real SalaryBox app include complaints about being "automatically signed out" — losing a
 * session on a rotation or a low-memory kill is a bad experience, and a staff member who has
 * to sign in again mid-shift will blame the app.
 */
@Singleton
class SessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val session: Flow<Session?> = dataStore.data.map { preferences -> preferences.toSession() }

    suspend fun current(): Session? = dataStore.data.first().toSession()

    suspend fun save(session: Session) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_ID] = session.userId
            preferences[KEY_ROLE] = session.role.name
            // Nullable: an admin has no staff record. `edit` cannot assign null, so the key
            // is removed for admins rather than stored as a literal "null" string.
            val staffId = session.staffId
            if (staffId == null) {
                preferences.remove(KEY_STAFF_ID)
            } else {
                preferences[KEY_STAFF_ID] = staffId
            }
            preferences[KEY_ISSUED_AT] = session.issuedAt.toEpochMilli()
            preferences[KEY_EXPIRES_AT] = session.expiresAt.toEpochMilli()
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_USER_ID)
            preferences.remove(KEY_ROLE)
            preferences.remove(KEY_STAFF_ID)
            preferences.remove(KEY_ISSUED_AT)
            preferences.remove(KEY_EXPIRES_AT)
        }
    }

    /**
     * A session past its expiry is treated as absent, so the UI routes to login even though a
     * stale record is still on disk.
     */
    private fun Preferences.toSession(): Session? {
        val userId = this[KEY_USER_ID] ?: return null
        val role = this[KEY_ROLE]?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return null
        val issuedAt = this[KEY_ISSUED_AT]?.let(Instant::ofEpochMilli) ?: return null
        val expiresAt = this[KEY_EXPIRES_AT]?.let(Instant::ofEpochMilli) ?: return null
        val session = Session(
            userId = userId,
            role = role,
            staffId = this[KEY_STAFF_ID],
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )
        return session.takeIf { it.isValidAt(Instant.now()) }
    }

    private companion object {
        val KEY_USER_ID = stringPreferencesKey("session_user_id")
        val KEY_ROLE = stringPreferencesKey("session_role")
        val KEY_STAFF_ID = stringPreferencesKey("session_staff_id")
        val KEY_ISSUED_AT = longPreferencesKey("session_issued_at")
        val KEY_EXPIRES_AT = longPreferencesKey("session_expires_at")
    }
}
