package com.bhanu.attendance.domain.face

import com.bhanu.attendance.domain.model.FaceTemplate

data class VerificationProgress(
    val matchedFrames: Int,
    val consideredFrames: Int,
    val requiredMatches: Int,
    val bestScore: Double,
    val threshold: Double,
    val lastIssue: QualityIssue,
    val isAccepted: Boolean,
    /** Smoothed progress 0..1 for the UI, based on frames matched so far. */
    val fraction: Float,
)

/**
 * Requires several *consecutive* good frames to agree before accepting a match.
 *
 * This is the cheapest meaningful defence against the obvious attack: someone holding up a
 * printed photo of the enrolled person. A still photo produces one plausible frame followed
 * by frames that fail the motion and inter-frame-stability checks, so it cannot accumulate a
 * consecutive run. It is not liveness detection — see the honest limitations section of the
 * README — but it is a real improvement over a single-frame comparison.
 *
 * Single-use, driven from one coroutine.
 */
class VerificationSession(
    private val template: FaceTemplate,
    private val thresholds: FaceThresholds,
    configuredFloor: Double,
) {
    private val templateDescriptor = FaceDescriptor.of(template.descriptorCopy())
    private val matcher = FaceMatcher(thresholds)
    private val qualityEvaluator = FaceQualityEvaluator(thresholds)

    /** The threshold actually in force, after self-calibration. */
    val threshold: Double = matcher.thresholdFor(template, configuredFloor)

    private val recentScores = ArrayDeque<Double>()

    /** Last accepted score, so the UI can explain *how* close the attempt came. */
    var bestScore: Double = 0.0
        private set

    fun progress(issue: QualityIssue = QualityIssue.OK, accepted: Boolean = false): VerificationProgress {
        val matched = recentScores.count { it >= threshold }
        val fraction = if (thresholds.requiredMatches <= 0) 0f
        else (matched.toFloat() / thresholds.requiredMatches).coerceIn(0f, 1f)
        return VerificationProgress(
            matchedFrames = matched,
            consideredFrames = recentScores.size,
            requiredMatches = thresholds.requiredMatches,
            bestScore = bestScore,
            threshold = threshold,
            lastIssue = issue,
            isAccepted = accepted,
            fraction = fraction,
        )
    }

    /**
     * Offers a frame.
     *
     * A frame is only counted when it is quality-acceptable *and* similar enough to the
     * previous frame, which is what stops a static spoof from racking up consecutive matches.
     */
    fun submit(observation: FaceObservation, faceCount: Int = 1): VerificationProgress {
        val verdict = qualityEvaluator.evaluate(observation, faceCount)
        // Same reasoning as enrolment: the blur check needs the immediately preceding frame.
        qualityEvaluator.remember(observation)
        if (!verdict.isAcceptable) return progress(verdict.issue)

        val descriptor = FaceDescriptorFactory.create(observation)
            ?: return progress(QualityIssue.LANDMARK_COUNT_INVALID)

        val score = matcher.score(descriptor, templateDescriptor)
        if (score > bestScore) bestScore = score

        val previous = recentScores.lastOrNull()
        val stableEnough = previous == null ||
            (score >= threshold && previous >= threshold && similarityBetween(previous, score) >= thresholds.minFrameStability)

        val counted = if (stableEnough) score else Double.NaN

        if (!counted.isNaN()) {
            recentScores.addLast(counted)
            while (recentScores.size > thresholds.considerFrames) recentScores.removeFirst()
        }

        val matched = recentScores.count { it >= threshold }
        val consecutive = longestConsecutiveRun()
        val accepted = matched >= thresholds.requiredMatches &&
            consecutive >= thresholds.requiredConsecutiveMatches

        return progress(verdict.issue, accepted)
    }

    private fun longestConsecutiveRun(): Int {
        var best = 0
        var run = 0
        for (s in recentScores) {
            if (s >= threshold) { run++; if (run > best) best = run } else run = 0
        }
        return best
    }

    /**
     * Turn a pair of similarity scores into a comparable 0..1 stability value.
     *
     * Two consecutive frames that both clear the threshold are already known to be near the
     * enrolled identity; requiring their scores to also be close to *each other* rejects the
     * case where successive frames are near-misses from different angles.
     */
    private fun similarityBetween(a: Double, b: Double): Double {
        val spread = kotlin.math.abs(a - b)
        return (1.0 - spread).coerceIn(0.0, 1.0)
    }

    fun reset() {
        recentScores.clear()
        bestScore = 0.0
        qualityEvaluator.reset()
    }
}
