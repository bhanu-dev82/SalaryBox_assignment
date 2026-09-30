package com.bhanu.attendance.data.seed

import androidx.room.withTransaction
import com.bhanu.attendance.data.local.AttendanceDatabase
import com.bhanu.attendance.data.local.entity.StaffEntity
import com.bhanu.attendance.data.security.PinHasher
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.data.mapper.toEntity
import com.bhanu.attendance.data.repository.AuthRepositoryImpl
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the admin account and two demo staff on first run.
 *
 * Without this, a fresh install has no way in: the admin is a database row like any other, so
 * the app would open to a login screen nobody could satisfy. Seeding is idempotent — it only
 * runs when the `staff` table is empty — so it cannot clobber real data on a later launch.
 *
 * Demo credentials are printed in the README and shown on the login screen. They are demo
 * data, not secrets, and they are hashed with exactly the same PBKDF2 path as any other PIN.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val database: AttendanceDatabase,
    private val pinHasher: PinHasher,
    private val logger: AppLogger,
) {
    suspend fun seedIfEmpty() {
        runCatching {
            if (database.staffDao().countAll() > 0) {
                logger.d(TAG, "Skipping seed: staff table is not empty")
                return
            }
            database.withTransaction {
                val now = Instant.parse("2026-09-21T09:00:00Z")
                val admin = StaffEntity(
                    id = AuthRepositoryImpl.ADMIN_ID,
                    employeeId = "ADMIN",
                    name = "Site Admin",
                    role = Role.ADMIN,
                    isActive = true,
                    faceEnrolledAt = null,
                    createdAt = now,
                    lastLoginAt = null,
                )
                database.staffDao().insertStaffWithCredential(
                    admin,
                    pinHasher.hash(ADMIN_PIN).toEntity(admin.id, now),
                )

                // One enrolled-by-design placeholder cannot be pre-enrolled, since enrolment
                // needs a real face. Both demo staff start unenrolled so the enrolment flow is
                // demonstrable end to end.
                for (staff in demoStaff(now)) {
                    database.staffDao().insertStaffWithCredential(
                        staff,
                        pinHasher.hash(STAFF_PIN).toEntity(staff.id, now),
                    )
                }
                logger.i(TAG, "Seeded admin and ${demoStaff(now).size} demo staff")
            }
        }.onFailure { logger.e(TAG, "Seeding failed", it) }
    }

    private fun demoStaff(now: Instant): List<StaffEntity> = listOf(
        StaffEntity(
            id = "demo-staff-1",
            employeeId = "EMP001",
            name = "Ravi Kumar",
            role = Role.STAFF,
            isActive = true,
            faceEnrolledAt = null,
            createdAt = now,
            lastLoginAt = null,
        ),
        StaffEntity(
            id = "demo-staff-2",
            employeeId = "EMP002",
            name = "Priya Sharma",
            role = Role.STAFF,
            isActive = true,
            faceEnrolledAt = null,
            createdAt = now,
            lastLoginAt = null,
        ),
    )

    companion object {
        private const val TAG = "Seeder"
        const val ADMIN_PIN = "1234"
        const val STAFF_PIN = "1111"
        val DEMO_CREDENTIALS = listOf(
            Triple("Admin", "ADMIN", ADMIN_PIN),
            Triple("Staff", "EMP001", STAFF_PIN),
            Triple("Staff", "EMP002", STAFF_PIN),
        )
    }
}
