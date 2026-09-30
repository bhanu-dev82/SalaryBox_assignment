package com.bhanu.attendance.domain.geo

import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.Geofence
import com.bhanu.attendance.domain.model.GeofenceState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class GeofenceTest {

    // Gurgaon, roughly where the company is based.
    private val site = Geofence(latitude = 28.4595, longitude = 77.0266, radiusMeters = 200.0, label = "Office")

    private fun at(lat: Double, lon: Double) = GeoLocation(lat, lon, 5f, "fused", Instant.EPOCH)

    @Test
    fun `distance between a point and itself is zero`() {
        assertThat(Haversine.distanceMeters(28.4595, 77.0266, 28.4595, 77.0266)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun `known distance between two Gurgaon points is plausible`() {
        // ~1 degree of latitude is ~111 km.
        val distance = Haversine.distanceMeters(28.4595, 77.0266, 29.4595, 77.0266)
        assertThat(distance).isWithin(2000.0).of(111_000.0)
    }

    @Test
    fun `a point inside the radius is INSIDE`() {
        // ~0.0005 degrees of latitude is roughly 55 m.
        val result = GeofenceEvaluator.evaluate(at(28.4600, 77.0266), site)
        assertThat(result.state).isEqualTo(GeofenceState.INSIDE)
        assertThat(result.distanceMeters).isNotNull()
    }

    @Test
    fun `a point well outside the radius is OUTSIDE`() {
        val result = GeofenceEvaluator.evaluate(at(28.50, 77.0266), site)
        assertThat(result.state).isEqualTo(GeofenceState.OUTSIDE)
    }

    @Test
    fun `no geofence configured is NOT_CONFIGURED`() {
        val result = GeofenceEvaluator.evaluate(at(28.4595, 77.0266), null)
        assertThat(result.state).isEqualTo(GeofenceState.NOT_CONFIGURED)
        assertThat(result.distanceMeters).isNull()
    }

    @Test
    fun `a geofence with no fix reports LOCATION_UNAVAILABLE rather than failing`() {
        // Location is advisory. A missing fix must never be reported as "outside the fence",
        // which would be a false accusation against the employee.
        val result = GeofenceEvaluator.evaluate(null, site)
        assertThat(result.state).isEqualTo(GeofenceState.LOCATION_UNAVAILABLE)
    }

    @Test
    fun `formatDistance is human readable`() {
        assertThat(GeofenceEvaluator.formatDistance(null)).isEqualTo("—")
        assertThat(GeofenceEvaluator.formatDistance(12.4)).isEqualTo("12 m")
        assertThat(GeofenceEvaluator.formatDistance(340.6)).isEqualTo("341 m")
        assertThat(GeofenceEvaluator.formatDistance(1400.0)).isEqualTo("1.4 km")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive radius is rejected at construction`() {
        Geofence(28.4595, 77.0266, radiusMeters = 0.0, label = "Bad")
    }
}
