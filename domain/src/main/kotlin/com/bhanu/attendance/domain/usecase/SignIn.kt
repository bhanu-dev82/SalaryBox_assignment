package com.bhanu.attendance.domain.usecase

import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Session
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.domain.repository.AuthRepository
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.time.TimeProvider
import com.bhanu.attendance.domain.validation.Validators
import javax.inject.Inject

/**
 * Sign-in for both roles.
 *
 * One use case rather than two because the UI must not be able to accidentally bypass the
 * staff-exists-and-is-active checks; keeping both paths here means the same rules are applied
 * regardless of which role the user *claims* to be.
 */
class SignInUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val staffRepository: StaffRepository,
    private val auditRepository: AuditRepository,
    private val timeProvider: TimeProvider,
) {
    suspend operator fun invoke(
        role: Role,
        identity: String,
        pin: String,
    ): Outcome<Session> {
        if (pin.isBlank()) {
            return Outcome.failure(
                com.bhanu.attendance.domain.outcome.AppError.Validation("PIN", "cannot be empty")
            )
        }

        val outcome = when (role) {
            Role.ADMIN -> authRepository.signInAdmin(pin)
            Role.STAFF -> {
                val normalised = Validators.normaliseEmployeeId(identity)
                if (normalised.isEmpty()) {
                    Outcome.failure(
                        com.bhanu.attendance.domain.outcome.AppError.Validation(
                            "Employee ID", "cannot be empty"
                        )
                    )
                } else {
                    authRepository.signInStaff(normalised, pin)
                }
            }
        }

        if (outcome is Outcome.Success) {
            val session = outcome.value
            auditRepository.log(
                actorId = session.userId,
                actorRole = session.role,
                action = AuditAction.SESSION_STARTED,
                targetStaffId = session.staffId,
                detail = "${session.role} signed in",
            )
            session.staffId?.let { staffRepository.recordLogin(it, timeProvider.now()) }
        }
        return outcome
    }
}

class SignOutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val auditRepository: AuditRepository,
) {
    suspend operator fun invoke() {
        val session = authRepository.currentSession()
        authRepository.signOut()
        if (session != null) {
            auditRepository.log(
                actorId = session.userId,
                actorRole = session.role,
                action = AuditAction.SESSION_ENDED,
                targetStaffId = session.staffId,
                detail = "Signed out",
            )
        }
    }
}
