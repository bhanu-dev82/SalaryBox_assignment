package com.bhanu.attendance.feature.auth.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val signIn: SignInUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /**
     * A `Channel`, not a `MutableStateFlow`, for one-shot effects.
     *
     * Navigating in response to state would re-navigate on every configuration change and on
     * process restore, because a `StateFlow` replays its current value to a new collector.
     */
    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    fun onRoleChange(role: Role) {
        _state.update { current ->
            current.copy(
                role = role,
                // The error belonged to the previous role's attempt; leaving it up would
                // suggest the new identity is also wrong.
                errorMessage = null,
                pin = "",
            )
        }
    }

    fun onEmployeeIdChange(value: String) {
        _state.update { it.copy(employeeId = value, errorMessage = null) }
    }

    fun onPinChange(value: String) {
        // Digits only, and capped, so a stray character cannot produce a validation error the
        // user cannot see the cause of.
        val filtered = value.filter(Char::isDigit).take(MAX_PIN_LENGTH)
        _state.update { it.copy(pin = filtered, errorMessage = null) }
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            when (val outcome = signIn(current.role, current.employeeId, current.pin)) {
                is Outcome.Success -> {
                    _state.update { it.copy(isSubmitting = false, pin = "") }
                    _events.send(LoginEvent.SignedIn(outcome.value.role))
                }

                is Outcome.Failure -> {
                    _state.update {
                        it.copy(isSubmitting = false, errorMessage = describe(outcome.error))
                    }
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun describe(error: AppError): String = when (error) {
        // Never reveal which half was wrong; that would help someone enumerate employee IDs.
        is AppError.InvalidCredentials -> "Incorrect employee ID or PIN"
        else -> error.message
    }

    private companion object {
        const val MAX_PIN_LENGTH = 12
    }
}
