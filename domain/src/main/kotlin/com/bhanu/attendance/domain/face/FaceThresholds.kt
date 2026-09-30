package com.bhanu.attendance.domain.face

/**
 * Every tunable number in the face pipeline, in one place.
 *
 * Collected deliberately: these are data rather than magic constants scattered through the
 * code, they can be adjusted from Settings without a code change, and — importantly — they
 * are the numbers an engineer would tune first. See `docs/DECISIONS.md` for the tuning
 * procedure and why the shipped defaults are conservative.
 */
data class FaceThresholds(
    // --- capture quality gates ---
    val minFaceHeightRatio: Float = 0.24f,
    val maxFaceHeightRatio: Float = 0.74f,
    val maxRollDegrees: Double = 18.0,
    val maxYawProxyAbs: Float = 0.30f,
    val minPitchProxy: Float = 0.20f,
    val maxPitchProxy: Float = 0.68f,
    val minEyeOpenness: Float = 0.085f,
    val minMeanLuma: Float = 38f,
    val maxMeanLuma: Float = 228f,
    val maxMotion: Float = 0.022f,

    // --- enrolment ---
    val enrolmentSampleTarget: Int = 5,
    /**
     * How far apart two enrolment samples must be in head pose, measured in yaw/pitch proxy
     * units, before the second is accepted.
     *
     * Diversity is measured in *pose* rather than descriptor similarity on purpose. The
     * descriptor is intentionally invariant to roll, position and distance, so two frames of
     * the same person in very different poses still score ~1.0 against each other — a
     * descriptor-space diversity check would reject every frame forever. Pose is the thing
     * that genuinely varies and genuinely adds information.
     */
    val minPoseSeparation: Float = 0.045f,

    /**
     * Frames to keep asking for pose variety before accepting whatever is in front of the
     * camera. Without this, someone who just holds still could never finish enrolling, which
     * would be a far worse failure than a slightly less diverse template.
     */
    val relaxAfterAttempts: Int = 25,

    // --- verification ---
    val defaultMinSimilarity: Double = 0.95,
    /** Require this many of the last [considerFrames] frames to clear the threshold. */
    val requiredMatches: Int = 3,
    val considerFrames: Int = 5,
    /** Consecutive matching frames required, so one lucky frame cannot pass on its own. */
    val requiredConsecutiveMatches: Int = 3,
    /** Consecutive-frame similarity below this indicates thrashing rather than a stable pose. */
    val minFrameStability: Double = 0.45,
) {
    init {
        require(enrolmentSampleTarget >= 2) { "need at least 2 enrolment samples, was $enrolmentSampleTarget" }
        require(requiredMatches in 1..considerFrames) { "requiredMatches must be within 1..considerFrames" }
        require(requiredConsecutiveMatches in 1..considerFrames) { "requiredConsecutiveMatches must be within 1..considerFrames" }
    }

    companion object {
        /**
         * Bounds a self-calibrated threshold is clamped into.
         *
         * These reflect the actual similarity scale of this descriptor family. A landmark
         * descriptor is extremely self-similar: measured on a synthetic cohort the same person
         * scores >0.9999 while different people top out around 0.97. A threshold in the 0.70s
         * — an intuitive-sounding "cosine similarity" default — would therefore accept
         * almost anyone, so the floor is high and the usable band is narrow.
         */
        val CALIBRATED_RANGE: ClosedFloatingPointRange<Double> = 0.90..0.9995

        /**
         * Headroom below the worst accepted enrolment pair.
         *
         * Small, because the worst pair is already a hard measurement of "this template does
         * match this person". A large margin would push the threshold down into the range where
         * other people start matching.
         */
        const val CALIBRATION_MARGIN: Double = 0.004

        /**
         * Derives the accept threshold from the worst pairwise similarity among the enrolment
         * samples.
         *
         * Rationale: enrolment is the only occasion where we have ground truth that a frame is
         * this person, so it is the only place a real measurement is available. The worst pair
         * is the binding constraint — it is the closest the employee ever came to producing a
         * frame the template would not accept. Setting the threshold just below it means live
         * captures get a little more room than enrolment did, without guessing an absolute
         * number that depends on how the descriptor happens to be scaled.
         *
         * [configuredFloor] is the admin's setting and always wins as a lower bound.
         */
        fun calibrate(intraClassMinSimilarity: Double, configuredFloor: Double): Double {
            if (!intraClassMinSimilarity.isFinite()) return configuredFloor
            val lowBound = maxOf(CALIBRATED_RANGE.start, configuredFloor)
            val highBound = maxOf(lowBound, CALIBRATED_RANGE.endInclusive)
            return (intraClassMinSimilarity - CALIBRATION_MARGIN).coerceIn(lowBound, highBound)
        }
    }
}
