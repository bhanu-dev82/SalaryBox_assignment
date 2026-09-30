package com.bhanu.attendance.feature.staff.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.domain.geo.GeofenceEvaluator
import com.bhanu.attendance.domain.geo.GeofenceResult
import com.bhanu.attendance.domain.model.AttendanceRecord
import com.bhanu.attendance.domain.model.Geofence
import com.bhanu.attendance.domain.model.GeofenceState
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Session
import com.bhanu.attendance.domain.repository.AttendanceRepository
import com.bhanu.attendance.domain.repository.AuthRepository
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.usecase.ObserveTodayPunchSummaryUseCase
import com.bhanu.attendance.domain.usecase.PunchAvailability
import com.bhanu.attendance.domain.usecase.PunchRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the home screen should do next, in one place. */
sealed interface StaffHomeEvent {
    data class OpenVerification(val staffId: String, val punchType: PunchType) : StaffHomeEvent
    data class ShowMessage(val message: String) : StaffHomeEvent
}

/**
 * Employee home screen and attendance history.
 *
 * All state derives from repository flows, so the screen is correct the instant it appears and
 * updates with no manual refresh. `flatMapLatest` on the session means signing in as someone
 * else cancels the previous person's queries, so one account's data can never linger on screen
 * after switching.
 */
@HiltViewModel
class StaffHomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    staffRepository: StaffRepository,
    private val settingsRepository: SettingsRepository,
    private val attendanceRepository: AttendanceRepository,
    observeTodayPunchSummary: ObserveTodayPunchSummaryUseCase,
) : ViewModel() {

    private val sessionFlow: StateFlow<Session?> = authRepository.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The signed-in person's display name, or empty when signed out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val staffNameFlow: Flow<String> = sessionFlow.flatMapLatest { session ->
        val staffId = session?.staffId
        if (staffId == null) {
            flowOf("")
        } else {
            // The session holds an id, not a name, so the name is read from the staff record.
            staffRepository.observeById(staffId).map { it?.name.orEmpty() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val punchSummary = sessionFlow.flatMapLatest { session ->
        val staffId = session?.staffId
        if (staffId == null) {
            flowOf(null)
        } else {
            observeTodayPunchSummary(staffId)
        }
    }

    val uiState: StateFlow<StaffHomeUiState> = kotlinx.coroutines.flow.combine(
        staffNameFlow,
        punchSummary,
    ) { name, summary ->
        StaffHomeUiState(
            staffName = name,
            summary = summary,
            isLoading = summary == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StaffHomeUiState())

    private val _events = Channel<StaffHomeEvent>(Channel.BUFFERED)
    val events: Flow<StaffHomeEvent> = _events.receiveAsFlow()

    /**
     * Loads history.
     *
     * `HistoryScope.ALL` is only ever reachable by an admin; the staff screen always uses
     * `MINE`. The distinction lives here rather than in the UI so the scope cannot be widened
     * by a navigation argument.
     */
    fun loadHistory(scope: HistoryScope) {
        val staffId = sessionFlow.value?.staffId
        if (scope == HistoryScope.MINE && staffId == null) {
            _historyState.value = HistoryUiState(isLoading = false, scope = scope)
            return
        }
        val records: Flow<List<AttendanceRecord>> = when (scope) {
            HistoryScope.MINE -> attendanceRepository.observeForStaff(staffId!!)
            HistoryScope.ALL -> attendanceRepository.observeAll()
        }
        viewModelScope.launch {
            val geofence = settingsRepository.currentGeofence()
            records.collect { list ->
                _historyState.value = HistoryUiState(
                    items = list.map { it.toHistoryItem(geofence) },
                    isLoading = false,
                    scope = scope,
                )
            }
        }
    }

    fun onPunchClicked() {
        val staffId = sessionFlow.value?.staffId
        when (val availability = uiState.value.availability) {
            is PunchAvailability.Blocked ->
                _events.trySend(StaffHomeEvent.ShowMessage(availability.reason))

            PunchAvailability.Complete ->
                _events.trySend(StaffHomeEvent.ShowMessage("Today's shift is already complete"))

            else -> {
                val type = PunchRules.nextPunchType(availability)
                if (type == null || staffId == null) {
                    _events.trySend(
                        StaffHomeEvent.ShowMessage("Could not start the punch. Sign out and sign in again.")
                    )
                } else {
                    _events.trySend(StaffHomeEvent.OpenVerification(staffId, type))
                }
            }
        }
    }

    fun onRecorded(punchType: PunchType) {
        _events.trySend(
            StaffHomeEvent.ShowMessage(
                if (punchType == PunchType.PUNCH_IN) "Punched in" else "Punched out"
            )
        )
    }

    private fun AttendanceRecord.toHistoryItem(geofence: Geofence?): HistoryItem {
        val fix = location
        val result: GeofenceResult? = GeofenceEvaluator.evaluate(fix, geofence)
        return HistoryItem(
            id = id,
            staffName = staffName,
            punchType = punchType,
            occurredAt = occurredAt,
            matchScore = matchScore,
            geofenceLabel = when {
                fix == null -> "No location recorded"
                result?.state == GeofenceState.INSIDE ->
                    "Inside the work site · ${fix.latitude.formatCoord()}, ${fix.longitude.formatCoord()}"
                result?.state == GeofenceState.OUTSIDE ->
                    "Outside the work site, ${GeofenceEvaluator.formatDistance(result.distanceMeters)} away · " +
                        "${fix.latitude.formatCoord()}, ${fix.longitude.formatCoord()}"
                else -> "${fix.latitude.formatCoord()}, ${fix.longitude.formatCoord()}"
            },
            selfiePath = selfiePath,
        )
    }

    private val _historyState = kotlinx.coroutines.flow.MutableStateFlow(HistoryUiState())
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()
}

/** Five decimal places is about a metre, which is enough to show the stored fix. */
private fun Double.formatCoord(): String = "%.5f".format(Locale.US, this)
