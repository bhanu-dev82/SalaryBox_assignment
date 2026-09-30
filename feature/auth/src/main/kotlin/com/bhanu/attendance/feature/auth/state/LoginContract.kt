package com.bhanu.attendance.feature.auth.state

import com.bhanu.attendance.domain.model.Role

/**
 * All login state in one immutable object.
 *
 * A single `StateFlow<LoginUiState>` rather than several `MutableStateFlow`s: the screen can
 * then never observe a combination of values that was never valid, and a recomposition
 * triggered by "role changed" and "error cleared" cannot interleave into a half-reset form.
 */
data class LoginUiState(
    val role: Role = Role.STAFF,
    val employeeId: String = "",
    val pin: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Employee-ID field is only meaningful for staff. */
    val showEmployeeId: Boolean get() = role == Role.STAFF

    val canSubmit: Boolean
        get() = !isSubmitting && pin.isNotBlank() && (showEmployeeId.not() || employeeId.isNotBlank())
}

/** One-shot events. Kept separate from state so they are not replayed on rotation. */
sealed interface LoginEvent {
    data class SignedIn(val role: Role) : LoginEvent
}
