package com.bhanu.attendance.domain.face

/**
 * Named landmark indices for the MediaPipe 478-point face mesh, plus the regional weights
 * used by [FaceDescriptorFactory].
 *
 * Index conventions differ between sources, so only the index *pairs* are trusted here and
 * never the "left"/"right" labels. `33/133` is one eye and `263/362` is the other.
 */
object Landmarks {
    const val RIGHT_EYE_OUTER_CORNER = 33
    const val RIGHT_EYE_INNER_CORNER = 133
    const val LEFT_EYE_OUTER_CORNER = 263
    const val LEFT_EYE_INNER_CORNER = 362

    const val NOSE_TIP = 4
    const val CHIN = 152
    const val FOREHEAD = 10

    /** Eye Aspect Ratio sets, ordered p1..p6 per the standard formula. */
    val EAR_EYE_A = intArrayOf(33, 160, 158, 133, 153, 144)
    val EAR_EYE_B = intArrayOf(362, 385, 387, 263, 373, 380)

    /** Eye rings. */
    val EYE_REGION: Set<Int> = buildSet {
        addAll(listOf(33, 133, 159, 145, 160, 158, 153, 144, 161, 246, 157, 173, 7, 163, 155, 154))
        addAll(listOf(263, 362, 387, 374, 385, 384, 386, 388, 466, 398, 382, 381, 380, 390, 373, 249))
        // iris rings (468..477) and centres — highly stable, barely affected by expression
        addAll((468..477).toList())
    }

    val LIPS_REGION: Set<Int> = buildSet {
        addAll(listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291))          // outer
        addAll(listOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)) // inner
        addAll(listOf(61, 291, 13, 14))                                            // corners + lip centres
    }

    val NOSE_REGION: Set<Int> = setOf(1, 2, 4, 5, 6, 19, 94, 97, 98, 168, 195, 197, 294, 324, 326, 327)

    /** The 36-point face oval (jaw + temples + forehead line). */
    val FACE_OVAL: Set<Int> = setOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378,
        400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54,
        103, 67, 109,
    )

    /**
     * Per-landmark weight applied before the descriptor is normalised.
     *
     * Rationale: identity lives in the eye/nose/mouth structure. Cheeks and forehead change
     * with expression, hair, and lighting, so they are down-weighted; the iris is stable and
     * gets the highest weight.
     */
    const val WEIGHT_IRIS = 1.6f
    const val WEIGHT_EYE = 1.35f
    const val WEIGHT_NOSE = 1.3f
    const val WEIGHT_LIPS = 1.25f
    const val WEIGHT_OVAL = 0.9f
    const val WEIGHT_DEFAULT = 0.7f

    private val weights: FloatArray by lazy {
        FloatArray(FaceObservation.EXPECTED_LANDMARK_COUNT) { index ->
            when {
                index in 468..477 -> WEIGHT_IRIS
                index in EYE_REGION -> WEIGHT_EYE
                index in LIPS_REGION -> WEIGHT_LIPS
                index in NOSE_REGION -> WEIGHT_NOSE
                index in FACE_OVAL -> WEIGHT_OVAL
                else -> WEIGHT_DEFAULT
            }
        }
    }

    fun weightAt(index: Int): Float = weights.getOrElse(index) { WEIGHT_DEFAULT }
}
