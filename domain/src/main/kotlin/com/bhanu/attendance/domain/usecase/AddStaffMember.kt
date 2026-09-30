package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Staff
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.time.IdGenerator
import com.bhanu.attendance.domain.time.TimeProvider
import com.bhanu.attendance.domain.validation.Validators
import com.bhanu.attendance.domain.validation.ValidationResult
import javax.inject.Inject

/**
 * Creates a staff member together with their initial PIN.
 *
 * Validation runs here rather than in the ViewModel so the same rules apply no matter which
 * surface calls it, and so they are unit-testable without Android.
 */
class AddStaffMemberUseCase @Inject constructor(
    private val staffRepository: StaffRepository,
    private val auditRepository: AuditRepository,
    private val timeProvider: TimeProvider,
    private val idGenerator: IdGenerator,
) {
    suspend operator fun invoke(
        name: String,
        employeeId: String,
        pin: String,
        actorId: String,
    ): Outcome<Staff> {
        val cleanName = Validators.normaliseName(name)
        (Validators.validateName(cleanName) as? ValidationResult.Invalid)?.let {
            return Outcome.failure(AppError.Validation("Name", describe(it)))
        }
        val normalisedId = Validators.normaliseEmployeeId(employeeId)
        (Validators.validateEmployeeId(normalisedId) as? ValidationResult.Invalid)?.let {
            return Outcome.failure(AppError.Validation("Employee ID", describe(it)))
        }
        (Validators.validatePin(pin) as? ValidationResult.Invalid)?.let {
            return Outcome.failure(AppError.Validation("PIN", describe(it)))
        }

        // Checked up front for a fast, clear message; the repository enforces it again
        // atomically, so this is a courtesy rather than the guarantee.
        if (staffRepository.getByEmployeeId(normalisedId) != null) {
            return Outcome.failure(AppError.DuplicateEmployeeId)
        }

        val staff = Staff(
            id = idGenerator.newId(),
            employeeId = normalisedId,
            name = cleanName,
            role = Role.STAFF,
            isActive = true,
            faceEnrolledAt = null,
            createdAt = timeProvider.now(),
            lastLoginAt = null,
        )

        val result = staffRepository.create(staff, pin)
        if (result is Outcome.Success) {
            runCatchingOutcome {
                auditRepository.log(
                    actorId = actorId,
                    actorRole = Role.ADMIN,
                    action = com.bhanu.attendance.domain.model.AuditAction.STAFF_CREATED,
                    targetStaffId = staff.id,
                    detail = "Created ${staff.name} ($normalisedId)",
                )
            }
        }
        return result
    }

    private fun describe(result: ValidationResult.Invalid): String = when (result.reason) {
        ValidationResult.Reason.EMPTY -> "cannot be empty"
        ValidationResult.Reason.TOO_SHORT -> "is too short"
        ValidationResult.Reason.TOO_LONG -> "is too long"
        ValidationResult.Reason.INVALID_CHARS -> "contains invalid characters"
        ValidationResult.Reason.NOT_NUMERIC -> "must be numeric"
        ValidationResult.Reason.WEAK -> "is too easily guessed; avoid repeated digits and 1234"
        ValidationResult.Reason.MALFORMED -> "is malformed"
    }
}
