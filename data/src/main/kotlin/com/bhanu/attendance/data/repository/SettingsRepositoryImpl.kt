package com.bhanu.attendance.data.repository

import com.bhanu.attendance.data.local.dao.SettingsDao
import com.bhanu.attendance.data.local.entity.SettingEntity
import com.bhanu.attendance.domain.face.FaceThresholds
import com.bhanu.attendance.domain.model.Geofence
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings held in the Room `settings` table.
 *
 * Values are stored as nullable strings with a `key` discriminator, written with REPLACE so a
 * setter needs no read-modify-write. Every parse is defensive: a corrupt or hand-edited row
 * falls back to the default rather than crashing a screen.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDao: SettingsDao,
) : SettingsRepository {

    override val geofence: Flow<Geofence?> = settingsDao.observe(KEY_GEOFENCE).map { parseGeofence(it) }

    override suspend fun currentGeofence(): Geofence? = parseGeofence(settingsDao.get(KEY_GEOFENCE))

    override suspend fun setGeofence(geofence: Geofence?) {
        runCatchingOutcome {
            if (geofence == null) {
                settingsDao.remove(KEY_GEOFENCE)
            } else {
                settingsDao.put(
                    SettingEntity(
                        KEY_GEOFENCE,
                        "${geofence.latitude}|${geofence.longitude}|${geofence.radiusMeters}|${geofence.label}",
                    )
                )
            }
        }
    }

    override val faceThresholds: Flow<FaceThresholds> =
        settingsDao.observe(KEY_MIN_SIMILARITY).map { parseThresholds(it) }

    override suspend fun currentFaceThresholds(): FaceThresholds =
        parseThresholds(settingsDao.get(KEY_MIN_SIMILARITY))

    override suspend fun setMinSimilarity(value: Double) {
        runCatchingOutcome {
            settingsDao.put(SettingEntity(KEY_MIN_SIMILARITY, value.toString()))
        }
    }

    override val punchOutEnabled: Flow<Boolean> = settingsDao.observe(KEY_PUNCH_OUT)
        .map { it?.toBooleanStrictOrNull() ?: DEFAULT_PUNCH_OUT_ENABLED }

    override suspend fun currentPunchOutEnabled(): Boolean =
        settingsDao.get(KEY_PUNCH_OUT)?.toBooleanStrictOrNull() ?: DEFAULT_PUNCH_OUT_ENABLED

    override suspend fun setPunchOutEnabled(enabled: Boolean) {
        runCatchingOutcome { settingsDao.put(SettingEntity(KEY_PUNCH_OUT, enabled.toString())) }
    }

    private fun parseGeofence(raw: String?): Geofence? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("|")
        if (parts.size != 4) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        val radius = parts[2].toDoubleOrNull() ?: return null
        if (radius <= 0) return null
        return Geofence(latitude, longitude, radius, parts[3])
    }

    /** Only the admin-tunable floor is persisted; the rest keep their shipped defaults. */
    private fun parseThresholds(raw: String?): FaceThresholds {
        val floor = raw?.toDoubleOrNull() ?: return FaceThresholds()
        if (!floor.isFinite() || floor <= 0.0 || floor >= 1.0) return FaceThresholds()
        return FaceThresholds(defaultMinSimilarity = floor)
    }

    private companion object {
        const val KEY_GEOFENCE = "geofence"
        const val KEY_MIN_SIMILARITY = "face_min_similarity"
        const val KEY_PUNCH_OUT = "punch_out_enabled"

        /**
         * Punch-out on by default: it is the behaviour a real attendance product has, and the
         * assignment says attendance details should be recorded, not just a single "I was here".
         */
        const val DEFAULT_PUNCH_OUT_ENABLED = true
    }
}
