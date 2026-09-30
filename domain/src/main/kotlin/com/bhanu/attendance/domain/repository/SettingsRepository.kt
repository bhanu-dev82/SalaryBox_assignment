package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.face.FaceThresholds
import com.bhanu.attendance.domain.model.Geofence
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val geofence: Flow<Geofence?>
    suspend fun currentGeofence(): Geofence?
    suspend fun setGeofence(geofence: Geofence?)

    val faceThresholds: Flow<FaceThresholds>
    suspend fun currentFaceThresholds(): FaceThresholds

    /** Admin-configured accept threshold; the floor for self-calibration. */
    suspend fun setMinSimilarity(value: Double)

    /** Whether a punch-out is expected in addition to the punch-in. */
    val punchOutEnabled: Flow<Boolean>
    suspend fun currentPunchOutEnabled(): Boolean
    suspend fun setPunchOutEnabled(enabled: Boolean)
}
