package com.bhanu.attendance.domain.face

import kotlin.math.abs

/**
 * Decides whether a single frame is good enough to enrol or to match against.
 *
 * Every rejection maps to a [QualityIssue] so the UI can tell the user how to fix it
 * ("move closer", "look at the camera", "find more light") instead of showing a generic
 * failure. That is the whole point: an opaque "something went wrong" is the exact complaint
 * users leave on the real SalaryBox Play Store listing.
 *
 * Pure and side-effect free, so every gate is unit-testable in isolation.
 */
class FaceQualityEvaluator(private val thresholds: FaceThresholds) {

    private var previousLandmarks: List<FaceLandmark>? = null

    /** Call when the capture session restarts so stale motion state does not leak across. */
    fun reset() {
        previousLandmarks = null
    }

    fun evaluate(
        observation: FaceObservation,
        faceCount: Int = 1,
        previous: List<FaceLandmark>? = previousLandmarks,
    ): QualityVerdict {
        if (observation.landmarkCount < FaceObservation.EXPECTED_LANDMARK_COUNT) {
            return QualityVerdict(QualityIssue.LANDMARK_COUNT_INVALID, null, false)
        }
        if (faceCount > 1) {
            return QualityVerdict(QualityIssue.MULTIPLE_FACES, null, false)
        }

        val pose = FacePoseEstimator.estimate(observation.landmarks) ?: run {
            return QualityVerdict(QualityIssue.LANDMARK_COUNT_INVALID, null, false)
        }

        // --- lighting ---
        if (observation.meanLuma < thresholds.minMeanLuma) {
            return QualityVerdict(QualityIssue.TOO_DARK, pose, false)
        }
        if (observation.meanLuma > thresholds.maxMeanLuma) {
            return QualityVerdict(QualityIssue.TOO_BRIGHT, pose, false)
        }

        // --- apparent size ---
        val box = FacePoseEstimator.boundingBox(observation.landmarks)
        val heightRatio = (box[3] - box[1])
        if (heightRatio < thresholds.minFaceHeightRatio) {
            return QualityVerdict(QualityIssue.FACE_TOO_SMALL, pose, false)
        }
        if (heightRatio > thresholds.maxFaceHeightRatio) {
            return QualityVerdict(QualityIssue.FACE_TOO_LARGE, pose, false)
        }

        // --- pose ---
        if (abs(pose.rollDegrees) > thresholds.maxRollDegrees) {
            return QualityVerdict(QualityIssue.TOO_MUCH_ROLL, pose, false)
        }
        if (abs(pose.yawProxy) > thresholds.maxYawProxyAbs) {
            return QualityVerdict(QualityIssue.TOO_MUCH_YAW, pose, false)
        }
        if (pose.pitchProxy < thresholds.minPitchProxy || pose.pitchProxy > thresholds.maxPitchProxy) {
            return QualityVerdict(QualityIssue.BAD_PITCH, pose, false)
        }

        // --- eyes ---
        val openness = FacePoseEstimator.eyeOpenness(observation.landmarks)
        if (openness == null || openness < thresholds.minEyeOpenness) {
            return QualityVerdict(QualityIssue.EYES_CLOSED, pose, false)
        }

        // --- motion, to avoid matching a smeared frame ---
        if (previous != null && previous.size == observation.landmarkCount) {
            val motion = meanMotion(previous, observation.landmarks)
            if (motion > thresholds.maxMotion) {
                return QualityVerdict(QualityIssue.EXCESSIVE_MOTION, pose, false)
            }
        }

        return QualityVerdict(QualityIssue.OK, pose, true)
    }

    /** Records the frame as the new motion reference. Call for every evaluated frame,
     * accepted or not, so motion compares against the immediately preceding frame.
     * Comparing only against the last *accepted* frame would turn a small pose
     * adjustment between samples into permanent "excessive motion". */
    fun remember(observation: FaceObservation) {
        previousLandmarks = observation.landmarks
    }

    private fun meanMotion(a: List<FaceLandmark>, b: List<FaceLandmark>): Float {
        var total = 0f
        val count = minOf(a.size, b.size)
        for (i in 0 until count) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return total / count
    }
}
