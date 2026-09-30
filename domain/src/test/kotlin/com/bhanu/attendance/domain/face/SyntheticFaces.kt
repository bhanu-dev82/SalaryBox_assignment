package com.bhanu.attendance.domain.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates synthetic but structurally valid 478-point face clouds.
 *
 * The descriptor pipeline is pure maths, so it can be tested exhaustively without a camera or
 * an emulator. A synthetic "face" is 478 points with the eye corners, nose, lips and jaw
 * written to the *real* MediaPipe mesh indices, so the canonical frame and the regional
 * weighting are both well-defined — and that is exactly what is under test.
 *
 * ### Why geometry is parameterised by ratios, not absolute sizes
 *
 * The descriptor is invariant to translation, scale and in-plane rotation. An earlier version
 * of this fixture gave each synthetic person a different absolute eye span, and produced a
 * discrimination of ~0.002 — because after normalisation the people were nearly the same
 * shape. What actually distinguishes faces *after* normalisation is the relationship between
 * features: interocular width relative to face height, how far the nose drops below the eye
 * line, mouth width relative to jaw width. [FaceGeometry] varies those ratios, which is both
 * more realistic and what makes the test meaningful.
 *
 * Two independent randomness sources, deliberately separated:
 *
 *  - [identitySeed] gives each person a stable [FaceGeometry] — their face.
 *  - [noise]/[noiseSeed] add fresh jitter on every call — capture conditions and sensor noise.
 */
object SyntheticFaces {

    const val LANDMARK_COUNT = FaceObservation.EXPECTED_LANDMARK_COUNT

    /** Facial proportions, all relative to a nominal face height of 1.0. */
    data class FaceGeometry(
        val eyeSpan: Float,
        val eyeLineY: Float,
        val noseDrop: Float,
        val mouthY: Float,
        val mouthWidth: Float,
        val jawWidth: Float,
        val chinY: Float,
    )

    fun geometryFor(identitySeed: Int): FaceGeometry {
        val r = Random(identitySeed)
        fun jitter(centre: Float, spread: Float) = centre + (r.nextFloat() - 0.5f) * spread
        return FaceGeometry(
            eyeSpan = jitter(0.17f, 0.09f),        // interocular half-distance
            eyeLineY = jitter(-0.10f, 0.05f),
            noseDrop = jitter(0.20f, 0.15f),       // nose tip below the eye line
            mouthY = jitter(0.28f, 0.07f),
            mouthWidth = jitter(0.13f, 0.11f),
            jawWidth = jitter(0.28f, 0.16f),
            chinY = jitter(0.42f, 0.10f),
        )
    }

    /** Feature index sets, so the same feature points are reused consistently. */
    private val NOSE_POINTS = Landmarks.NOSE_REGION.toList()
    private val LIPS_OUTER = listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)
    private val LIPS_INNER = listOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
    private val JAW_POINTS = Landmarks.FACE_OVAL.toList()

    fun create(
        identitySeed: Int = 1,
        yawShift: Float = 0f,
        rollShiftDegrees: Float = 0f,
        scale: Float = 1f,
        translateX: Float = 0f,
        translateY: Float = 0f,
        noise: Float = 0f,
        noiseSeed: Int = 0,
        meanLuma: Float = 120f,
        eyesOpen: Boolean = true,
        imageWidth: Int = 1080,
        imageHeight: Int = 1920,
    ): FaceObservation {
        val g = geometryFor(identitySeed)
        val sensor = Random(noiseSeed * 7919 + 13)

        // Stable per-person perturbation of every landmark on the mesh.
        //
        // This matters more than it looks. The descriptor weights all 478 points, and real
        // faces genuinely differ across the whole mesh -- cheekbones, jawline, forehead -- not
        // just at the eyes and nose. A fixture where those points were identical across
        // "different people" left the descriptor with essentially nothing to separate on, and
        // reported ~0.004 separation with overlap. Varying them per identity is both more
        // realistic and what makes the discrimination test meaningful.
        val identity = Random(identitySeed * 104_729 + 17)
        val stableRadius = FloatArray(LANDMARK_COUNT) { 1f + (identity.nextFloat() - 0.5f) * 0.60f }
        val stableAngle = FloatArray(LANDMARK_COUNT) { (identity.nextFloat() - 0.5f) * 0.30f }

        val points = ArrayList<FaceLandmark>(LANDMARK_COUNT)
        for (i in 0 until LANDMARK_COUNT) {
            val angle = (2.0 * PI * i / LANDMARK_COUNT).toFloat() + stableAngle[i]
            val jitter = if (noise > 0f) sensor.nextFloat() * noise - noise / 2f else 0f
            val depthJitter = if (noise > 0f) sensor.nextFloat() * noise - noise / 2f else 0f
            val r = stableRadius[i] * (1f + jitter)
            val x = cos(angle) * g.jawWidth * r
            val y = sin(angle) * 0.42f * r
            val z = depthJitter * 0.4f
            points.add(FaceLandmark(x, y, z))
        }

        fun put(index: Int, x: Float, y: Float, z: Float = 0f) {
            points[index] = FaceLandmark(x, y, z)
        }

        // --- eyes: corners, lids, iris ---
        val eyeY = g.eyeLineY
        put(Landmarks.RIGHT_EYE_OUTER_CORNER, -g.eyeSpan + yawShift, eyeY)
        put(Landmarks.RIGHT_EYE_INNER_CORNER, -g.eyeSpan * 0.22f, eyeY)
        put(Landmarks.LEFT_EYE_INNER_CORNER, g.eyeSpan * 0.22f, eyeY)
        put(Landmarks.LEFT_EYE_OUTER_CORNER, g.eyeSpan, eyeY)
        put(468, -g.eyeSpan * 0.5f, eyeY) // iris centre, image-left eye
        put(473, g.eyeSpan * 0.5f, eyeY)  // iris centre, image-right eye

        val lidGap = if (eyesOpen) g.eyeSpan * 0.16f else 0f
        writeEyeRing(points, Landmarks.EAR_EYE_A, g, -g.eyeSpan * 0.5f, eyeY, yawShift, lidGap)
        writeEyeRing(points, Landmarks.EAR_EYE_B, g, g.eyeSpan * 0.5f, eyeY, yawShift, lidGap)

        // --- nose: a vertical column dropping from the brow to the tip ---
        val noseY = eyeY + g.noseDrop
        for (i in NOSE_POINTS) {
            val t = (i % 7) / 7f
            val x = yawShift * 1.4f * (1f - t * 0.5f) + (i % 3 - 1) * g.eyeSpan * 0.10f
            put(i, x, eyeY + g.noseDrop * t)
        }
        put(Landmarks.NOSE_TIP, yawShift * 1.4f, noseY, -0.05f)

        // --- lips ---
        for ((n, index) in LIPS_OUTER.withIndex()) {
            val t = n / (LIPS_OUTER.size - 1f)
            put(index, (t * 2f - 1f) * g.mouthWidth, g.mouthY, -0.02f)
        }
        for ((n, index) in LIPS_INNER.withIndex()) {
            val t = n / (LIPS_INNER.size - 1f)
            put(index, (t * 2f - 1f) * g.mouthWidth * 0.6f, g.mouthY + 0.02f, -0.01f)
        }

        // --- jaw / oval: widen the lower half so the contour is not a plain ellipse ---
        for (index in JAW_POINTS) {
            val current = points[index]
            val lowerHalf = if (current.y > 0f) 1.25f else 0.92f
            put(index, current.x * lowerHalf, current.y, current.z)
        }
        put(Landmarks.CHIN, 0f, g.chinY, 0.05f)
        put(Landmarks.FOREHEAD, 0f, eyeY - (g.chinY - eyeY) * 1.05f, -0.05f)

        // --- capture nuisances ---
        // The canonical face above is built at roughly unit height. Scale it so it occupies a
        // realistic share of the frame; otherwise the bounding-box height gate rejects every
        // synthetic capture as FACE_TOO_LARGE.
        val frameScale = 0.42f * scale
        for (i in points.indices) {
            val p = points[i]
            points[i] = FaceLandmark(p.x * frameScale, p.y * frameScale, p.z * frameScale)
        }

        val radians = Math.toRadians(rollShiftDegrees.toDouble()).toFloat()
        val cosR = cos(radians)
        val sinR = sin(radians)
        val rolled = points.map { p ->
            FaceLandmark(
                x = p.x * cosR - p.y * sinR + translateX,
                y = p.x * sinR + p.y * cosR + translateY,
                z = p.z,
            )
        }

        return FaceObservation(rolled, imageWidth, imageHeight, meanLuma)
    }

    /**
     * Fills one eye's lid ring plus its iris.
     *
     * Note [indices]: positions are written to `points[indices[n]]`, never to `points[n]`.
     * An earlier version wrote to the array position instead of the landmark index, which
     * quietly relocated the eye geometry to landmarks 0..5 and made the blink gate measure
     * noise — the gate passed for closed eyes as happily as for open ones.
     */
    private fun writeEyeRing(
        points: MutableList<FaceLandmark>,
        indices: IntArray,
        g: FaceGeometry,
        centreX: Float,
        centreY: Float,
        yawShift: Float,
        lidGap: Float,
    ) {
        val irisR = g.eyeSpan * 0.22f
        val span = g.eyeSpan * 0.85f
        val half = span / 2f
        fun at(slot: Int, x: Float, y: Float) {
            points[indices[slot]] = FaceLandmark(x + yawShift, y, 0f)
        }
        // p1/p4 corners, p2/p3 upper lid, p5/p6 lower lid
        at(0, centreX - half, centreY)
        at(1, centreX - half * 0.3f, centreY - lidGap)
        at(2, centreX + half * 0.3f, centreY - lidGap)
        at(3, centreX + half, centreY)
        at(4, centreX + half * 0.3f, centreY + lidGap)
        at(5, centreX - half * 0.3f, centreY + lidGap)

        // The remaining lid-ring landmarks, so the whole eye is anatomically coherent and the
        // regional weighting has something meaningful to act on.
        val upperRing = listOf(161, 246, 160, 159, 158, 157, 173)
        val lowerRing = listOf(7, 163, 144, 153, 154, 155)
        upperRing.forEachIndexed { n, index ->
            val t = (n + 1) / (upperRing.size + 1f)
            points[index] = FaceLandmark(
                centreX - half + span * t + yawShift,
                centreY - lidGap * (1f - 2f * kotlin.math.abs(t - 0.5f)),
                0f,
            )
        }
        lowerRing.forEachIndexed { n, index ->
            val t = (n + 1) / (lowerRing.size + 1f)
            points[index] = FaceLandmark(
                centreX - half + span * t + yawShift,
                centreY + lidGap * (1f - 2f * kotlin.math.abs(t - 0.5f)),
                0f,
            )
        }
        // Iris ring (4 points) around the iris centre.
        for (n in 0 until 4) {
            val a = n * (PI / 2.0).toFloat()
            points[469 + n] = FaceLandmark(
                centreX + irisR * cos(a) + yawShift,
                centreY + irisR * sin(a),
                0f,
            )
        }
    }
}
