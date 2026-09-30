package com.bhanu.attendance.domain.face

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Estimates head pose from landmarks using geometric proxies rather than a pose model.
 *
 * These are deliberately *proxies*, not metric angles. They are only ever compared against
 * loose bounds to decide "is this head straight enough to match against", and a document
 * re-aligns capture guidance from them. Presenting them as precise angles would overstate
 * what landmark geometry can deliver.
 */
object FacePoseEstimator {

    fun estimate(landmarks: List<FaceLandmark>): FacePose? {
        if (landmarks.size < FaceObservation.EXPECTED_LANDMARK_COUNT) return null

        val eyeA = landmarks[Landmarks.RIGHT_EYE_OUTER_CORNER]
        val eyeB = landmarks[Landmarks.LEFT_EYE_OUTER_CORNER]
        val nose = landmarks[Landmarks.NOSE_TIP]
        val chin = landmarks[Landmarks.CHIN]

        val interocular = distance(eyeA, eyeB)
        if (interocular < 1e-6f) return null

        val eyeMidX = (eyeA.x + eyeB.x) / 2f
        val eyeMidY = (eyeA.y + eyeB.y) / 2f

        // Roll: angle of the eye axis in the image plane. y grows downward, so the sign is
        // inverted to match the usual "positive = clockwise" reading.
        val roll = -Math.toDegrees(atan2((eyeB.y - eyeA.y).toDouble(), (eyeB.x - eyeA.x).toDouble()))

        // Yaw: when the head turns, the nose tip drifts toward one eye corner. Normalising the
        // asymmetry by interocular distance makes it distance-invariant.
        val distToA = distance(nose, eyeA)
        val distToB = distance(nose, eyeB)
        val yawProxy = ((distToB - distToA) / interocular).coerceIn(-1f, 1f)

        // Pitch: the nose tip's position between the eye line and the chin, as a fraction of
        // that span.
        //
        // The eye line is used as the top reference rather than Landmarks.FOREHEAD. Index 10
        // is the top of the forehead near the hairline, well above the brow, which biases the
        // ratio upward and makes a perfectly frontal face look like it is looking down. The
        // eye line is a landmark pair we already trust, is not affected by hair, and puts a
        // frontal face at roughly 0.38 -- comfortably inside the accepted band.
        val span = chin.y - eyeMidY
        val pitchProxy = if (abs(span) < 1e-6f) {
            0.38f
        } else {
            ((nose.y - eyeMidY) / span).coerceIn(0f, 1f)
        }

        return FacePose(rollDegrees = roll, yawProxy = yawProxy, pitchProxy = pitchProxy)
    }

    /**
     * Eye Aspect Ratio, averaged over both eyes.
     *
     * `EAR = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)` where p1/p4 are the eye corners and the
     * other four are upper/lower lid points. Drops as the eye closes, so it makes a cheap,
     * effective blink gate.
     */
    fun eyeOpenness(landmarks: List<FaceLandmark>): Float? {
        if (landmarks.size < FaceObservation.EXPECTED_LANDMARK_COUNT) return null
        val a = ear(landmarks, Landmarks.EAR_EYE_A) ?: return null
        val b = ear(landmarks, Landmarks.EAR_EYE_B) ?: return null
        return (a + b) / 2f
    }

    private fun ear(landmarks: List<FaceLandmark>, indices: IntArray): Float? {
        if (indices.any { it >= landmarks.size }) return null
        val p = indices.map { landmarks[it] }
        val cornerDistance = distance(p[0], p[3])
        if (cornerDistance < 1e-6f) return null
        val vertical = distance(p[1], p[5]) + distance(p[2], p[4])
        return vertical / (2f * cornerDistance)
    }

    /** Bounding box of the landmark cloud, in normalised image coordinates. */
    fun boundingBox(landmarks: List<FaceLandmark>): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in landmarks) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    private fun distance(a: FaceLandmark, b: FaceLandmark): Float {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    /** Degrees of yaw implied by a yaw proxy, for display only. */
    fun yawProxyToDegrees(proxy: Float): Double =
        Math.toDegrees(asin(proxy.coerceIn(-1f, 1f).toDouble()))
}
