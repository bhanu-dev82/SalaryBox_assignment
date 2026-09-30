package com.bhanu.attendance.feature.admin.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.domain.model.AuditAction
import com.bhanu.attendance.domain.model.Role
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.AttendanceRepository
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.usecase.AddStaffMemberUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Admin staff list and detail.
 *
 * The list is driven entirely by repository flows, so a change made on the detail pane (or by
 * another admin on another device, in a real backend) appears without any manual refresh.
 */
@HiltViewModel
class StaffListViewModel @Inject constructor(
    private val staffRepository: StaffRepository,
    private val attendanceRepository: AttendanceRepository,
    private val auditRepository: AuditRepository,
    private val addStaffMember: AddStaffMemberUseCase,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val dialogState = MutableStateFlow(false)
    private val selectedId = MutableStateFlow<String?>(null)

    /**
     * Staff rows for the admin list.
     *
     * Filters out [Role.ADMIN]. The admin is a row in the same table so that it is
     * authenticated by exactly the same code path as staff, but it has no face, no punches
     * and is not something an admin should be able to "enrol" or "deactivate" from this
     * screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val items: Flow<List<StaffListItem>> = combine(
        staffRepository.observeAll(),
        attendanceRepository.observeAll(),
    ) { staff, records ->
        val counts = records.groupingBy { it.staffId }.eachCount()
        staff.filter { it.role == com.bhanu.attendance.domain.model.Role.STAFF }.map { s ->
            StaffListItem(
                id = s.id,
                name = s.name,
                employeeId = s.employeeId,
                isFaceEnrolled = s.isFaceEnrolled,
                isActive = s.isActive,
                punchCount = counts[s.id] ?: 0,
            )
        }
    }

    val uiState: StateFlow<StaffListUiState> = combine(
        items,
        query,
        dialogState,
    ) { list, currentQuery, dialogVisible ->
        StaffListUiState(
            items = list,
            query = currentQuery,
            isLoading = false,
            isAddDialogVisible = dialogVisible,
        )
    }.stateIn(
        scope = viewModelScope,
        // Keeps the list warm for 5s after the screen goes away, so returning is instant
        // without holding a database observer open indefinitely.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StaffListUiState(),
    )

    /**
     * Detail-pane state.
     *
     * `flatMapLatest` means selecting a different staff member cancels the previous one's
     * query rather than leaving two streams running and racing to write the same state.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val detailState: StateFlow<StaffDetailUiState> = selectedId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(StaffDetailUiState())
            } else {
                combine(
                    staffRepository.observeById(id),
                    attendanceRepository.observeForStaff(id),
                ) { staff, records ->
                    StaffDetailUiState(
                        staff = staff?.let { s ->
                            StaffListItem(
                                id = s.id,
                                name = s.name,
                                employeeId = s.employeeId,
                                isFaceEnrolled = s.isFaceEnrolled,
                                isActive = s.isActive,
                                punchCount = records.size,
                            )
                        },
                        punchCount = records.size,
                        isLoading = false,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StaffDetailUiState())

    /**
     * Staff id whose PIN-reset dialog is open, or null.
     *
     * Held in the ViewModel rather than as composable-local state so the dialog survives the
     * Activity recreation a fold or rotation causes — a dialog that vanishes mid-edit is a
     * data-entry bug, not a cosmetic one.
     */
    private val _pinResetTarget = MutableStateFlow<String?>(null)
    val pinResetTarget: StateFlow<String?> = _pinResetTarget.asStateFlow()

    fun openPinReset(staffId: String) = _pinResetTarget.update { staffId }

    fun closePinReset() = _pinResetTarget.update { null }

    private val _events = Channel<StaffListEvent>(Channel.BUFFERED)
    val events: Flow<StaffListEvent> = _events.receiveAsFlow()

    private val _addStaffState = MutableStateFlow(AddStaffUiState())
    val addStaffState: StateFlow<AddStaffUiState> = _addStaffState.asStateFlow()

    fun onQueryChange(value: String) = query.update { value }

    fun selectStaff(id: String?) = selectedId.update { id }

    fun openAddDialog() {
        _addStaffState.value = AddStaffUiState()
        _events.trySend(StaffListEvent.OpenAddDialog)
    }

    fun closeAddDialog() {
        _addStaffState.update { it.copy(isSubmitting = false, errorMessage = null) }
        _events.trySend(StaffListEvent.CloseAddDialog)
    }

    fun submitAddStaff(name: String, employeeId: String, pin: String) {
        val current = _addStaffState.value
        if (current.isSubmitting) return
        _addStaffState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            when (val outcome = addStaffMember(name, employeeId, pin, actorId = ADMIN_ACTOR_ID)) {
                is Outcome.Success -> {
                    _addStaffState.value = AddStaffUiState()
                    _events.trySend(StaffListEvent.CloseAddDialog)
                    // Select the new person so the detail pane shows them on a wide screen,
                    // which makes the creation visibly take effect.
                    selectedId.value = outcome.value.id
                    _events.trySend(StaffListEvent.ShowMessage("${outcome.value.name} added"))
                }

                is Outcome.Failure -> {
                    _addStaffState.update {
                        it.copy(isSubmitting = false, errorMessage = outcome.error.message)
                    }
                }
            }
        }
    }

    fun resetPin(staffId: String, newPin: String) {
        viewModelScope.launch {
            when (val outcome = staffRepository.resetPin(staffId, newPin)) {
                is Outcome.Success -> {
                    auditRepository.log(
                        actorId = ADMIN_ACTOR_ID,
                        actorRole = Role.ADMIN,
                        action = AuditAction.PIN_RESET,
                        targetStaffId = staffId,
                    )
                    _events.trySend(StaffListEvent.ShowMessage("PIN reset"))
                }

                is Outcome.Failure ->
                    _events.trySend(StaffListEvent.ShowMessage(outcome.error.message))
            }
        }
    }

    fun setActive(staffId: String, active: Boolean) {
        viewModelScope.launch {
            runCatchingOutcome { staffRepository.setActive(staffId, active) }
            auditRepository.log(
                actorId = ADMIN_ACTOR_ID,
                actorRole = Role.ADMIN,
                action = if (active) AuditAction.STAFF_UPDATED else AuditAction.STAFF_DEACTIVATED,
                targetStaffId = staffId,
            )
            _events.trySend(
                StaffListEvent.ShowMessage(if (active) "Staff activated" else "Staff deactivated")
            )
        }
    }

    fun recordEnrolmentAbandoned(staffId: String, reason: String) {
        viewModelScope.launch {
            auditRepository.log(
                actorId = ADMIN_ACTOR_ID,
                actorRole = Role.ADMIN,
                action = AuditAction.FACE_ENROLMENT_FAILED,
                targetStaffId = staffId,
                detail = reason,
            )
        }
    }

    companion object {
        /**
         * The seeded admin's id.
         *
         * A constant because this build has exactly one admin. With a real backend this would
         * come from the session; keeping it in one place makes that swap a single change.
         */
        const val ADMIN_ACTOR_ID = "admin"
    }
}

data class AddStaffUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean get() = !isSubmitting
}
