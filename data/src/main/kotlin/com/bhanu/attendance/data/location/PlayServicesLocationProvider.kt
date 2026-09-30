package com.bhanu.attendance.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.repository.LocationProvider
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.bhanu.attendance.core.common.dispatchers.AppDispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Location via the fused provider.
 *
 * Contract from [LocationProvider]: must never throw, and must never block attendance. So
 * every failure mode — permission denied, GPS off, provider unavailable, a fix that simply
 * never arrives — resolves to `null` within [timeoutMillis]. A null fix is recorded honestly
 * as `LOCATION_UNAVAILABLE`; it is never a reason to refuse someone their attendance.
 */
@Singleton
class PlayServicesLocationProvider @Inject constructor(
    private val context: Context,
    private val client: FusedLocationProviderClient,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
) : LocationProvider {

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Guarded by hasPermission() immediately above.
    override suspend fun currentLocation(timeoutMillis: Long): GeoLocation? {
        if (!hasPermission()) {
            logger.d(TAG, "Location permission not granted; recording without a fix")
            return null
        }
        return withTimeoutOrNull(timeoutMillis) {
            val current = awaitCurrentLocation()
            current?.let { it.toGeoLocation() }
        } ?: run {
            // Timed out or nothing available; try a last-known fix, which is usually good
            // enough to decide inside/outside a 200 m geofence.
            logger.d(TAG, "No current fix within ${timeoutMillis}ms; trying last known")
            lastKnownLocation()?.toGeoLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val request = CurrentLocationRequest.Builder()
            // BALANCED_POWER rather than HIGH_ACCURACY: a geofence decision does not need
            // metre-grade precision, and the high-power radio would be a real battery cost
            // on a device that is being used as a work attendance handset all day.
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_FIX_AGE_MILLIS)
            .setDurationMillis(REQUEST_DURATION_MILLIS)
            .build()

        client.getCurrentLocation(request, null)
            .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location) }
            .addOnFailureListener { error ->
                logger.w(TAG, "getCurrentLocation failed", error)
                if (continuation.isActive) continuation.resume(null)
            }
            .addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): Location? = withContext(dispatchers.io) {
        runCatching {
            suspendCancellableCoroutine { continuation ->
                client.lastLocation
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
                    .addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
            }
        }.getOrNull()
    }

    private fun Location.toGeoLocation(): GeoLocation = GeoLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        provider = provider,
        capturedAt = Instant.ofEpochMilli(time),
    )

    private companion object {
        const val TAG = "LocationProvider"
        const val MAX_FIX_AGE_MILLIS = 2 * 60 * 1000L
        const val REQUEST_DURATION_MILLIS = 4_000L
    }
}
