package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.geo.GeofenceEvaluator
import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Staff
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.AttendanceRepository
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.domain.repository.LocationProvider
import com.bhanu.attendance.domain.repository.SelfieStorage
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.domain.time.IdGenerator
import com.bhanu.attendance.domain.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** Everything the camera pipeline produced for one successful verification. */
data class VerifiedPunch(
    val punchType: PunchType,
    val selfieJpeg: ByteArray,
    val matchScore: Double,
    val matchedFrames: Int,
    val consideredFrames: Int,
) {
    // ByteArray in a data class: identity equals would be wrong, so compare content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedPunch) return false
        return punchType == other.punchType &&
            selfieJpeg.contentEquals(other.selfieJpeg) &&
            matchScore == other.matchScore &&
            matchedFrames == other.matchedFrames &&
            consideredFrames == other.consideredFrames
    }

    override fun hashCode(): Int {
        var result = punchType.hashCode()
        result = 31 * result + selfieJpeg.contentHashCode()
        result = 31 * result + matchScore.hashCode()
        result = 31 * result + matchedFrames
        result = 31 * result + consideredFrames
        return result
    }
}

/**
 * Records a verified punch: the transactional heart of the app.
 *
 * Order of operations is deliberate:
 *
 *  1. Rules are re-checked **here**, not trusted from the UI. The employee's screen may be
 *     stale, or they may be tapping twice, so the sequence is re-validated at the boundary.
 *  2. Enrolment is re-checked, for the same reason.
 *  3. The selfie is written first. If the database write then fails, the record is absent and
 *     an orphan image is far cheaper to reconcile than a row pointing at a missing file.
 *  4. Location is captured with a bounded timeout and **never blocks the punch**. A user with
 *     a denied permission or a weak GPS still gets their attendance recorded; the record is
 *     simply marked as having no location, which is honest.
 *  5. The database's unique index is the final arbiter, so a double-tap cannot produce two
 *     punch-ins even under a race.
 */
class MarkPunchUseCase @Inject constructor(
    private val attendanceRepository: AttendanceRepository,
    private val faceTemplateRepository: FaceTemplateRepository,
    private val selfieStorage: SelfieStorage,
    private val locationProvider: LocationProvider,
    private val settingsRepository: SettingsRepository,
    private val auditRepository: AuditRepository,
    private val timeProvider: TimeProvider,
    private val idGenerator: IdGenerator,
) {
    suspend operator fun invoke(staff: Staff, verified: VerifiedPunch): Outcome<AttendanceRecord> {
        if (!staff.isActive) return Outcome.failure(AppError.StaffInactive(staff.name))
        if (!staff.isFaceEnrolled || faceTemplateRepository.get(staff.id) == null) {
            return Outcome.failure(AppError.NotEnrolled)
        }

        val now = timeProvider.now()
        val zone = timeProvider.zone()
        val today = com.bhanu.attendance.domain.time.ShiftClock.localDateOf(now, zone)

        val todaysRecords = attendanceRepository.getForStaffAndDate(staff.id, today)
        val punchOutEnabled = settingsRepository.currentPunchOutEnabled()
        val summary = PunchRules.evaluate(todaysRecords, today, punchOutEnabled, isFaceEnrolled = true)

        val expected = PunchRules.nextPunchType(summary.availability)
        if (expected == null) {
            return Outcome.failure(
                when (summary.availability) {
                    PunchAvailability.Complete -> AppError.AlreadyPunchedOut
                    is PunchAvailability.Blocked -> AppError.Unexpected(summary.availability.reason, null)
                    else -> AppError.Unexpected("Punch sequence in an unexpected state", null)
                }
            )
        }
        if (expected != verified.punchType) {
            return Outcome.failure(
                if (expected == PunchType.PUNCH_IN) AppError.NotPunchedIn else AppError.AlreadyPunchedIn
            )
        }

        val recordId = idGenerator.newId()

        val selfiePath = try {
            selfieStorage.save(verified.selfieJpeg, recordId, today)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Outcome.failure(AppError.SelfieWriteFailed)
        }

        // Advisory only: null location yields a record flagged LOCATION_UNAVAILABLE.
        val location: GeoLocation? = runCatchingOutcome {
            locationProvider.currentLocation(LOCATION_TIMEOUT_MILLIS)
        }.getOrNull()

        val geofence = settingsRepository.currentGeofence()
        val geofenceResult = GeofenceEvaluator.evaluate(location, geofence)

        val record = AttendanceRecord(
            id = recordId,
            staffId = staff.id,
            staffName = staff.name,
            employeeId = staff.employeeId,
            punchType = verified.punchType,
            occurredAt = now,
            zoneId = zone.id,
            utcOffsetSeconds = zone.rules.getOffset(now).totalSeconds,
            localDate = today,
            selfiePath = selfiePath,
            matchScore = verified.matchScore,
            matchedFrames = verified.matchedFrames,
            consideredFrames = verified.consideredFrames,
            location = location,
            geofenceState = geofenceResult.state,
            createdAt = now,
        )

        return when (val stored = attendanceRepository.record(record)) {
            is Outcome.Success -> {
                runCatchingOutcome {
                    auditRepository.log(
                        actorId = staff.id,
                        actorRole = Role.STAFF,
                        action = AuditAction.ATTENDANCE_MARKED,
                        targetStaffId = staff.id,
                        detail = "${verified.punchType} score=${"%.3f".format(verified.matchScore)} " +
                            "frames=${verified.matchedFrames}/${verified.consideredFrames} " +
                            "geofence=${geofenceResult.state}",
                    )
                }
                Outcome.success(stored.value)
            }

            is Outcome.Failure -> {
                // Roll the orphaned selfie back so a failed punch leaves nothing behind.
                runCatchingOutcome { selfieStorage.delete(selfiePath) }
                Outcome.failure(stored.error)
            }
        }
    }

    companion object {
        /** Bounded so a flaky GPS can never make the punch feel like it has hung. */
        const val LOCATION_TIMEOUT_MILLIS: Long = 6_000
    }
}

/** Convenience for callers that have a staff id rather than a [Staff]. */
class RecordRejectedPunchUseCase @Inject constructor(
    private val auditRepository: AuditRepository,
) {
    suspend operator fun invoke(staff: Staff, reason: String) {
        runCatchingOutcome {
            auditRepository.log(
                actorId = staff.id,
                actorRole = Role.STAFF,
                action = AuditAction.ATTENDANCE_REJECTED,
                targetStaffId = staff.id,
                detail = reason,
            )
        }
    }
}

/**
 * Records an enrolment attempt.
 *
 * Takes primitives rather than a [Staff]: the caller is a camera screen that has a staff id
 * and a name, and fabricating a whole domain object just to log it invited duplication.
 */
class RecordEnrolmentAuditUseCase @Inject constructor(
    private val auditRepository: AuditRepository,
) {
    suspend fun enrolled(
        adminId: String,
        staffId: String,
        staffName: String,
        sampleCount: Int,
        /** The worst pairwise similarity, which is what the threshold is calibrated from. */
        intraClassMinSimilarity: Double,
    ) {
        runCatchingOutcome {
            auditRepository.log(
                actorId = adminId,
                actorRole = Role.ADMIN,
                action = AuditAction.FACE_ENROLLED,
                targetStaffId = staffId,
                detail = "$sampleCount samples, intra=${"%.4f".format(intraClassMinSimilarity)}",
            )
        }
    }

    suspend fun failed(adminId: String, staffId: String, reason: String) {
        runCatchingOutcome {
            auditRepository.log(
                actorId = adminId,
                actorRole = Role.ADMIN,
                action = AuditAction.FACE_ENROLMENT_FAILED,
                targetStaffId = staffId,
                detail = reason.take(200),
            )
        }
    }
}
