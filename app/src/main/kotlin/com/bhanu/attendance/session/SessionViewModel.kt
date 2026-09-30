package com.bhanu.attendance.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.model.Session
import com.bhanu.attendance.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Session, plus whether the persisted value has finished loading. */
data class SessionUiState(
    val session: Session? = null,
    val isLoaded: Boolean = false,
) {
    val isSignedIn: Boolean get() = session != null
    val role: Role get() = session?.role ?: Role.STAFF
    val staffId: String? get() = session?.staffId
}

/**
 * Owns the app-wide session.
 *
 * `isLoaded` exists so the UI can tell "signed out" apart from "not read from storage yet".
 * Without it, every cold start flashes the login screen before the DataStore read completes,
 * which looks exactly like being signed out at random — the specific complaint in the real
 * app's public reviews.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    val session: StateFlow<Session?> = authRepository.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // The flow is the session. Reading it and discarding the value left `session`
            // null forever, so staff home could render (it watches the repository directly)
            // while navigation still saw no staff id and ignored Punch in.
            //
            // The catch matters: if the read throws, the app must still leave the loading
            // state, otherwise a corrupt preferences file bricks the app on every launch.
            runCatching {
                authRepository.session.collect { session ->
                    _state.value = SessionUiState(session = session, isLoaded = true)
                }
            }.onFailure {
                _state.update { it.copy(isLoaded = true) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}
