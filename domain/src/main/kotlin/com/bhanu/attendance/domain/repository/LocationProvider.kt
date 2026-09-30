package com.bhanu.attendance.domain.repository

import com.bhanu.attendance.domain.model.GeoLocation

/**
 * Supplies the current location fix.
 *
 * Contract: implementations must **never throw**. A missing permission, a disabled GPS radio
 * and a timeout all return `null`, because location is advisory here and must never be able to
 * block marking attendance.
 */
interface LocationProvider {
    suspend fun currentLocation(timeoutMillis: Long): GeoLocation?
}
