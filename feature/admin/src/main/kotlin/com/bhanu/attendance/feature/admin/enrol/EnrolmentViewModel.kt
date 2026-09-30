package com.bhanu.attendance.feature.admin.enrol

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.data.face.FaceEngine
import com.bhanu.attendance.core.designsystem.camera.FaceCameraSession
import com.bhanu.attendance.data.face.FaceFrameResult
import com.bhanu.attendance.domain.face.EnrolmentSession
import com.bhanu.attendance.domain.face.FaceObservation
import com.bhanu.attendance.domain.face.FaceThresholds
import com.bhanu.attendance.domain.face.QualityIssue
import com.bhanu.attendance.domain.model.FaceTemplate
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.domain.time.TimeProvider
import com.bhanu.attendance.domain.usecase.RecordEnrolmentAuditUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EnrolmentUiState(
    val staffId: String = "",
    val staffName: String = "",
    val accepted: Int = 0,
    val target: Int = 5,
    val currentIssue: QualityIssue = QualityIssue.LANDMARK_COUNT_INVALID,
    val isComplete: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: AppError? = null,
    val thresholdPreview: Double? = null,
    val intraClassSimilarity: Double? = null,
) {
    val fraction: Float get() = if (target <= 0) 0f else (accepted.toFloat() / target).coerceIn(0f, 1f)
}

/**
 * Drives face enrolment for one staff member.
 *
 * The [EnrolmentSession] lives here rather than in the composable, so its state survives
 * configuration changes. On a tablet, folding the device or rotating mid-enrolment
 * recreates the Activity; a session held in composition would restart from zero and the
 * person would be asked to start over.
 */
@HiltViewModel
class EnrolmentViewModel @Inject constructor(
    private val faceTemplateRepository: FaceTemplateRepository,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    private val audit: RecordEnrolmentAuditUseCase,
    private val faceEngine: FaceEngine,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(EnrolmentUiState())
    val state: StateFlow<EnrolmentUiState> = _state.asStateFlow()

    /**
     * Held outside the UI state because it is mutable and not meaningful to display directly.
     * Recreated whenever the staff member changes, so two enrolments never share samples.
     */
    private var session: EnrolmentSession? = null
    private var thresholds: FaceThresholds = FaceThresholds()
    private var actorId: String = "admin"

    fun start(staffId: String, staffName: String, adminId: String) {
        if (session != null && _state.value.staffId == staffId) return
        actorId = adminId
        viewModelScope.launch {
            thresholds = settingsRepository.currentFaceThresholds()
            session = EnrolmentSession(thresholds = thresholds, staffId = staffId)
            _state.value = EnrolmentUiState(
                staffId = staffId,
                staffName = staffName,
                target = thresholds.enrolmentSampleTarget,
            )
        }
    }

    /**
     * Subscribes to landmark results and feeds them to the enrolment session.
     *
     * The engine delivers on its own worker thread, so the state update is a `MutableStateFlow`
     * write — thread-safe — and the composable collects on the main thread.
     */
    fun observeFrames() {
        viewModelScope.launch {
            faceEngine.observations.collect { result ->
                when (result) {
                    is FaceFrameResult.Detected -> onFrame(result.observation, result.faceCount)
                    FaceFrameResult.NoFace -> _state.update { it.copy(currentIssue = QualityIssue.FACE_TOO_SMALL) }
                }
            }
        }
    }

    private fun onFrame(observation: FaceObservation, faceCount: Int) {
        val active = session ?: return
        val progress = active.submit(observation, faceCount)
        _state.update { current ->
            current.copy(
                accepted = progress.accepted,
                currentIssue = progress.lastIssue,
                isComplete = progress.isComplete,
            )
        }
        if (progress.isComplete) saveTemplate()
    }

    private fun saveTemplate() {
        val active = session ?: return
        val current = _state.value
        if (current.isSaving || current.isSaved) return

        val template: FaceTemplate = active.buildTemplate(timeProvider.now()) ?: run {
            _state.update {
                it.copy(
                    isComplete = false,
                    currentIssue = QualityIssue.EXCESSIVE_MOTION,
                    error = AppError.Unexpected("Could not build a template from the captured samples", null),
                )
            }
            return
        }

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            faceTemplateRepository.upsert(template)
            audit.enrolled(
                adminId = actorId,
                staffId = template.staffId,
                staffName = current.staffName,
                sampleCount = template.sampleCount,
                intraClassMinSimilarity = template.intraClassMinSimilarity,
            )
            val calibrated = com.bhanu.attendance.domain.face.FaceMatcher(thresholds)
                .thresholdFor(template, thresholds.defaultMinSimilarity)
            _state.update {
                it.copy(
                    isSaving = false,
                    isSaved = true,
                    thresholdPreview = calibrated,
                    intraClassSimilarity = template.intraClassMinSimilarity,
                )
            }
            logger.i(TAG, "Enrolled face for ${current.staffName}, threshold=$calibrated")
        }
    }

    /** Called when the person cancels, so the audit trail records the attempt. */
    fun abandon(reason: String) {
        val staffId = _state.value.staffId
        if (staffId.isNotEmpty() && !_state.value.isSaved) {
            viewModelScope.launch {
                audit.failed(actorId, staffId, reason)
            }
        }
    }

    fun onCameraFailure(detail: String) {
        _state.update { it.copy(error = AppError.NoCamera(detail)) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Builds the camera session.
     *
     * The ViewModel is the right place to construct it because it is the component that owns
     * the injected [FaceEngine] and [AppLogger] the session needs, and because both must be
     * the *same* instance enrolment uses. The composable only binds and releases it.
     */
    fun createCameraSession(context: android.content.Context): FaceCameraSession =
        FaceCameraSession(context = context, faceEngine = faceEngine, logger = logger)

    /** A frame the screen can hand straight to the analyser. */
    fun submitBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            val decimated = faceEngine.decimate(bitmap, FaceCameraSession.ANALYSIS_MAX_DIMENSION)
            val luma = faceEngine.meanLuma(decimated)
            faceEngine.submit(decimated, luma)
        }
    }

    private companion object {
        const val TAG = "Enrolment"
    }
}
