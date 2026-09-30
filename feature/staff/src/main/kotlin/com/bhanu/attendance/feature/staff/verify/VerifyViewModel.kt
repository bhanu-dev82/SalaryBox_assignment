package com.bhanu.attendance.feature.staff.verify

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.core.designsystem.camera.CameraFailure
import com.bhanu.attendance.core.designsystem.camera.FaceCameraSession
import com.bhanu.attendance.data.face.FaceEngine
import com.bhanu.attendance.data.face.FaceFrameResult
import com.bhanu.attendance.domain.face.FaceObservation
import com.bhanu.attendance.domain.face.FaceThresholds
import com.bhanu.attendance.domain.face.QualityIssue
import com.bhanu.attendance.domain.face.VerificationProgress
import com.bhanu.attendance.domain.face.VerificationSession
import com.bhanu.attendance.data.storage.SelfieEncoder
import com.bhanu.attendance.domain.model.GeoLocation
import com.bhanu.attendance.domain.model.PunchType
import com.bhanu.attendance.domain.model.Staff
import com.bhanu.attendance.domain.outcome.AppError
import com.bhanu.attendance.domain.outcome.Outcome
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.domain.repository.LocationProvider
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.domain.usecase.MarkPunchUseCase
import com.bhanu.attendance.domain.usecase.RecordRejectedPunchUseCase
import com.bhanu.attendance.domain.usecase.VerifiedPunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Where the verification flow currently is. */
enum class VerifyStage {
    /** Camera is running, waiting for enough matching frames. */
    VERIFYING,

    /** Face matched and a selfie was captured; the user confirms to write the record. */
    AWAITING_CONFIRMATION,

    /** The record was written. */
    RECORDED,
}

data class VerifyUiState(
    val staffId: String = "",
    val staffName: String = "",
    val punchType: PunchType = PunchType.PUNCH_IN,
    val stage: VerifyStage = VerifyStage.VERIFYING,
    val issue: QualityIssue = QualityIssue.POSITIONING,
    val matchedFrames: Int = 0,
    val consideredFrames: Int = 0,
    val requiredMatches: Int = 3,
    val progress: Float = 0f,
    val bestScore: Double = 0.0,
    val threshold: Double = 0.0,
    val capturedJpeg: ByteArray? = null,
    val isRecording: Boolean = false,
    val error: AppError? = null,
    /** Set once the punch row has been written. Null means no fix was stored. */
    val savedLocation: GeoLocation? = null,
    /** Bumped on retake so the camera binds again after a failed start. */
    val cameraGeneration: Int = 0,
) {
    val isBusy: Boolean get() = isRecording
}

/**
 * Drives face verification and the punch that follows.
 *
 * The order is deliberate: **verify first, capture second, write last.** Nothing touches the
 * database until a selfie exists and the person has confirmed, so a cancelled or failed
 * verification leaves no orphan rows and no dangling image paths.
 *
 * The [VerificationSession] is held here rather than in the composable so it survives the
 * Activity recreation that a fold, a rotation or a window-resize causes — otherwise a
 * half-finished verification would silently restart.
 */
@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val faceTemplateRepository: FaceTemplateRepository,
    private val staffRepository: StaffRepository,
    private val settingsRepository: SettingsRepository,
    private val markPunch: MarkPunchUseCase,
    private val recordRejected: RecordRejectedPunchUseCase,
    private val locationProvider: LocationProvider,
    private val faceEngine: FaceEngine,
    private val selfieEncoder: SelfieEncoder,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()

    private var verificationSession: VerificationSession? = null
    private var staff: Staff? = null
    private var cameraSession: FaceCameraSession? = null
    private var observeJob: Job? = null

    /** JPEG of the frame currently being checked. Written before inference returns. */
    private val latestFrameJpeg = AtomicReference<ByteArray?>(null)
    private var lastJpegEncodeAt: Long = 0L

    private var locationPrimed = false

    /**
     * Asks for a fix while the face check is still running.
     *
     * The punch reads location again when it is saved. This call only warms the radio,
     * so a cold GPS is less likely to come back empty in the few seconds after confirm.
     */
    fun prepareLocation() {
        if (locationPrimed) return
        locationPrimed = true
        viewModelScope.launch {
            runCatching { locationProvider.currentLocation(LOCATION_WARMUP_MILLIS) }
                .onFailure { logger.w(TAG, "Location warmup failed", it) }
        }
    }

    fun start(staffId: String, punchType: PunchType) {
        if (_state.value.staffId == staffId && verificationSession != null) return
        viewModelScope.launch {
            val person = staffRepository.getById(staffId)
            val template = faceTemplateRepository.get(staffId)
            val thresholds = settingsRepository.currentFaceThresholds()

            staff = person
            if (person == null) {
                _state.update { it.copy(error = AppError.StaffNotFound(staffId)) }
                return@launch
            }
            if (template == null || template.descriptor.isEmpty()) {
                _state.update {
                    it.copy(staffId = staffId, staffName = person.name, error = AppError.NotEnrolled)
                }
                return@launch
            }

            // A template written by an older descriptor must not be compared against: the
            // geometry changed, so every score would be meaningless.
            if (template.modelVersion != com.bhanu.attendance.domain.face.EnrolmentSession.MODEL_VERSION) {
                _state.update {
                    it.copy(
                        staffId = staffId,
                        staffName = person.name,
                        error = AppError.Unexpected(
                            "This face template was made with an older version of the app. Please re-enrol.",
                            null,
                        ),
                    )
                }
                return@launch
            }

            verificationSession = VerificationSession(
                template = template,
                thresholds = thresholds,
                configuredFloor = thresholds.defaultMinSimilarity,
            )
            _state.value = VerifyUiState(
                staffId = staffId,
                staffName = person.name,
                punchType = punchType,
                requiredMatches = thresholds.requiredMatches,
                threshold = verificationSession?.threshold ?: 0.0,
            )
        }
    }

    /**
     * A decoded, upright frame from the camera pipeline. Waits until inference finishes so
     * the bitmap is still alive when MediaPipe reads it.
     *
     * The punch photo is throttled, not encoded on every frame: JPEG compression of a 720p
     * frame costs ~20-50ms on the analyser thread, and 99% of those bytes are discarded
     * because only the accepted frames matter. Encoding at most once per 300ms still leaves
     * a photo within 300ms of acceptance — same person, same light — while cutting the
     * per-frame cost by roughly two thirds at typical inference rates.
     */
    suspend fun onAnalysedBitmap(bitmap: android.graphics.Bitmap) {
        val decimated = faceEngine.decimate(bitmap, FaceCameraSession.ANALYSIS_MAX_DIMENSION)
        try {
            if (_state.value.stage == VerifyStage.VERIFYING && !decimated.isRecycled) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastJpegEncodeAt >= JPEG_THROTTLE_MILLIS) {
                    val jpeg = runCatching {
                        selfieEncoder.encode(decimated, maxDimension = 960, quality = 80)
                    }.getOrNull()
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        latestFrameJpeg.set(jpeg)
                        lastJpegEncodeAt = now
                    }
                }
            }
            val luma = faceEngine.meanLuma(decimated)
            faceEngine.submit(decimated, luma)
        } finally {
            if (decimated !== bitmap && !decimated.isRecycled) decimated.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun createCameraSession(context: Context): FaceCameraSession =
        FaceCameraSession(context = context, faceEngine = faceEngine, logger = logger).also {
            cameraSession = it
        }

    fun observeFrames() {
        if (observeJob?.isActive == true) return
        observeJob = viewModelScope.launch {
            when (val ready = faceEngine.initialise()) {
                is Outcome.Failure -> {
                    _state.update { it.copy(error = ready.error) }
                    return@launch
                }
                is Outcome.Success -> Unit
            }
            faceEngine.observations.collect { result ->
                when (result) {
                    is FaceFrameResult.Detected -> onFrame(result.observation, result.faceCount)
                    FaceFrameResult.NoFace -> onNoFace()
                }
            }
        }
    }

    private fun onNoFace() {
        val session = verificationSession
        if (_state.value.stage != VerifyStage.VERIFYING) return
        if (session == null) {
            _state.update { it.copy(issue = QualityIssue.FACE_TOO_SMALL) }
            return
        }
        // Feed the gap into the session so a stale streak cannot survive a walk-away.
        val progress = session.onNoFace()
        _state.update {
            it.copy(
                issue = progress.lastIssue,
                matchedFrames = progress.matchedFrames,
                consideredFrames = progress.consideredFrames,
                requiredMatches = progress.requiredMatches,
                progress = progress.fraction,
                bestScore = progress.bestScore,
            )
        }
    }

    private fun onFrame(observation: FaceObservation, faceCount: Int) {
        val session = verificationSession ?: return
        // Once a selfie is captured the decision is made; further frames must not change it.
        if (_state.value.stage != VerifyStage.VERIFYING) return

        val progress: VerificationProgress = session.submit(observation, faceCount)
        _state.update { current ->
            current.copy(
                issue = progress.lastIssue,
                matchedFrames = progress.matchedFrames,
                consideredFrames = progress.consideredFrames,
                requiredMatches = progress.requiredMatches,
                progress = progress.fraction,
                bestScore = progress.bestScore,
            )
        }
        if (progress.isAccepted) {
            val jpeg = latestFrameJpeg.get()
            if (jpeg == null || jpeg.isEmpty()) {
                logger.w(TAG, "Matched face had no photo bytes")
                failWith(AppError.SelfieWriteFailed)
            } else {
                _state.update {
                    it.copy(
                        stage = VerifyStage.AWAITING_CONFIRMATION,
                        capturedJpeg = jpeg,
                        issue = QualityIssue.OK,
                        error = null,
                    )
                }
            }
        }
    }

    fun confirm() {
        val current = _state.value
        val person = staff ?: return
        val jpeg = current.capturedJpeg ?: return
        if (current.isRecording) return

        _state.update { it.copy(isRecording = true, error = null) }
        viewModelScope.launch {
            val verified = VerifiedPunch(
                punchType = current.punchType,
                selfieJpeg = jpeg,
                matchScore = current.bestScore,
                matchedFrames = current.matchedFrames,
                consideredFrames = current.consideredFrames.coerceAtLeast(current.matchedFrames)
                    .coerceAtLeast(1),
            )
            when (val outcome = markPunch(person, verified)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            isRecording = false,
                            stage = VerifyStage.RECORDED,
                            savedLocation = outcome.value.location,
                            error = null,
                        )
                    }
                }

                is Outcome.Failure -> {
                    _state.update { it.copy(isRecording = false) }
                    // The punch sequence may have moved on (someone else already punched in),
                    // so restart verification rather than leaving a stale selfie on screen.
                    if (outcome.error == AppError.AlreadyPunchedIn ||
                        outcome.error == AppError.AlreadyPunchedOut
                    ) {
                        resetForRetry()
                    }
                    _state.update { it.copy(error = outcome.error) }
                    recordRejected(person, outcome.error.message)
                }
            }
        }
    }

    fun retry() {
        resetForRetry()
    }

    private fun resetForRetry() {
        verificationSession?.reset()
        latestFrameJpeg.set(null)
        lastJpegEncodeAt = 0L
        _state.update { current ->
            current.copy(
                stage = VerifyStage.VERIFYING,
                issue = QualityIssue.POSITIONING,
                matchedFrames = 0,
                consideredFrames = 0,
                progress = 0f,
                bestScore = 0.0,
                capturedJpeg = null,
                isRecording = false,
                savedLocation = null,
                cameraGeneration = current.cameraGeneration + 1,
                error = null,
            )
        }
    }

    fun giveUp() {
        val person = staff
        if (person != null && _state.value.stage != VerifyStage.RECORDED) {
            viewModelScope.launch {
                recordRejected(person, "Verification abandoned at score ${"%.3f".format(_state.value.bestScore)}")
            }
        }
    }

    fun onCameraFailure(failure: CameraFailure) {
        failWith(
            when (failure) {
                is CameraFailure.Bind -> AppError.NoCamera(failure.detail)
                CameraFailure.NoCamera -> AppError.NoCamera("No front camera is available on this device.")
                CameraFailure.CaptureFailed -> AppError.SelfieWriteFailed
            }
        )
    }

    fun onPermissionDenied() {
        failWith(AppError.PermissionDeniedCamera)
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun failWith(error: AppError) {
        logger.w(TAG, "Verification failed: ${error.message}")
        _state.update { it.copy(error = error) }
    }

    override fun onCleared() {
        // Do not leave the camera bound if the ViewModel goes away without onDispose running.
        runCatching { cameraSession?.release() }
        super.onCleared()
    }

    private companion object {
        const val TAG = "Verify"
        const val LOCATION_WARMUP_MILLIS = 15_000L
        /** At most one punch-photo encode per window; see onAnalysedBitmap. */
        const val JPEG_THROTTLE_MILLIS = 300L
    }
}
