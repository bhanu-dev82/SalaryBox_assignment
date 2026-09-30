package com.bhanu.attendance.domain.face

import com.bhanu.attendance.domain.face.FaceObservation.Companion.EXPECTED_LANDMARK_COUNT
import kotlin.math.sqrt

/**
 * Turns a raw landmark cloud into a comparable [FaceDescriptor].
 *
 * The pipeline removes the three nuisance factors that would otherwise dominate matching:
 *
 *  1. **Translation** — subtract the centroid, so where the face sits in frame is irrelevant.
 *  2. **Scale** — divide by the RMS radius from the centroid, so distance from the camera is
 *     irrelevant.
 *  3. **In-plane rotation** — build an orthonormal basis from the eye axis and the eye-line
 *     to nose direction, then express every landmark in that basis. A head rolled 20 degrees
 *     now produces the same descriptor as one held upright.
 *
 * Out-of-plane rotation (yaw/pitch) is *not* normalised away; it is instead bounded by the
 * quality gates in [FaceQualityEvaluator], which refuse to match a badly turned head. See
 * docs/DECISIONS.md for why that trade-off was taken.
 */
object FaceDescriptorFactory {

    private class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
        infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x,
        )
        val magnitude: Double get() = sqrt(x * x + y * y + z * z)

        fun normalized(): Vec3 {
            val m = magnitude
            return if (m < 1e-9) Vec3(0.0, 0.0, 0.0) else Vec3(x / m, y / m, z / m)
        }

        /** Remove the component of this vector along [axis], which must be unit length. */
        fun rejectAlong(axis: Vec3): Vec3 {
            val d = this dot axis
            return Vec3(x - axis.x * d, y - axis.y * d, z - axis.z * d)
        }
    }

    /** Returns null when the landmark cloud is unusable. */
    fun create(observation: FaceObservation): FaceDescriptor? {
        val points = observation.landmarks
        if (points.size < EXPECTED_LANDMARK_COUNT) return null

        val vectors = ArrayList<Vec3>(points.size)
        for (p in points) vectors.add(Vec3(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()))

        // --- 1. translation ---
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        for (v in vectors) { cx += v.x; cy += v.y; cz += v.z }
        val n = vectors.size.toDouble()
        val centroid = Vec3(cx / n, cy / n, cz / n)
        val centred = vectors.map { it - centroid }

        // --- canonical basis ---
        val eyeA = centred[Landmarks.RIGHT_EYE_OUTER_CORNER]
        val eyeB = centred[Landmarks.LEFT_EYE_OUTER_CORNER]
        val eyeMid = (eyeA + eyeB) * 0.5
        val nose = centred[Landmarks.NOSE_TIP]

        val xAxis = (eyeB - eyeA).normalized()
        if (xAxis.magnitude < 1e-9) return null
        val yAxis = (nose - eyeMid).rejectAlong(xAxis).normalized()
        if (yAxis.magnitude < 1e-9) return null
        val zAxis = xAxis cross yAxis

        // --- 2. express in basis, 3. scale-normalise by RMS radius ---
        val projected = Array(centred.size) { i ->
            val d = centred[i]
            Vec3(d dot xAxis, d dot yAxis, d dot zAxis)
        }
        var sumSquares = 0.0
        for (v in projected) sumSquares += v dot v
        val rms = sqrt(sumSquares / projected.size)
        if (rms < 1e-9) return null
        val inverseRms = 1.0 / rms

        val values = FloatArray(projected.size * 3)
        for (i in projected.indices) {
            val w = Landmarks.weightAt(i)
            val v = projected[i]
            values[i * 3] = (v.x * inverseRms * w).toFloat()
            values[i * 3 + 1] = (v.y * inverseRms * w).toFloat()
            values[i * 3 + 2] = (v.z * inverseRms * w).toFloat()
        }
        return FaceDescriptor.of(values)
    }
}
