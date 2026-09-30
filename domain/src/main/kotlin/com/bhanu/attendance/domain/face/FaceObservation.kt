package com.bhanu.attendance.domain.face

/**
 * A single landmark point in MediaPipe's normalised image space.
 *
 * `x` and `y` are normalised by image width/height (so 0..1 spans the frame) and `z` is
 * depth relative to the head centre, where more negative is closer to the camera.
 */
data class FaceLandmark(val x: Float, val y: Float, val z: Float)

/**
 * One frame's worth of face data, expressed in domain types so that every piece of face
 * logic is testable on the JVM without CameraX, MediaPipe or a device.
 */
data class FaceObservation(
    val landmarks: List<FaceLandmark>,
    val imageWidth: Int,
    val imageHeight: Int,
    /** Mean luma of the analysed frame, 0..255. */
    val meanLuma: Float,
) {
    val landmarkCount: Int get() = landmarks.size

    companion object {
        /** MediaPipe FaceLandmarker emits 478 points: 468 mesh + 10 iris. */
        const val EXPECTED_LANDMARK_COUNT: Int = 478
    }
}

/** Head pose expressed as geometric proxies, derived from landmarks. See [FacePoseEstimator]. */
data class FacePose(
    val rollDegrees: Double,
    /** Asymmetric proxy in roughly -1..1; 0 is frontal. Sign is only meaningful per-frame. */
    val yawProxy: Float,
    /** Nose position between brow line and chin, roughly 0.2..0.7 when facing the camera. */
    val pitchProxy: Float,
)

/** Why a frame was rejected. Drives the on-screen coaching text. */
enum class QualityIssue {
    OK,
    /** Camera is up and no verdict has arrived yet. Not a failure. */
    POSITIONING,
    /** Another sample is needed from a slightly different angle. Not a failure. */
    NEED_DIFFERENT_POSE,
    LANDMARK_COUNT_INVALID,
    FACE_TOO_SMALL,
    FACE_TOO_LARGE,
    MULTIPLE_FACES,
    TOO_MUCH_ROLL,
    TOO_MUCH_YAW,
    BAD_PITCH,
    EYES_CLOSED,
    TOO_DARK,
    TOO_BRIGHT,
    EXCESSIVE_MOTION,
    LOW_CONFIDENCE,
}

data class QualityVerdict(
    val issue: QualityIssue,
    val pose: FacePose?,
    val isAcceptable: Boolean,
) {
    companion object {
        val OK: QualityVerdict = QualityVerdict(QualityIssue.OK, null, true)
    }
}
