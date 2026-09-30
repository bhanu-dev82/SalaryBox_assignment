package com.bhanu.attendance.domain.face

import com.bhanu.attendance.domain.model.FaceTemplate
import java.time.Instant

data class EnrolmentProgress(
    val accepted: Int,
    val target: Int,
    val lastIssue: QualityIssue,
    val isComplete: Boolean,
) {
    val fraction: Float get() = if (target <= 0) 0f else (accepted.toFloat() / target).coerceIn(0f, 1f)
}

/**
 * Collects several good frames and folds them into one template.
 *
 * Why more than one frame: a single frame is one snapshot of one expression at one angle.
 * Averaging several captures centres the template on the person's actual geometry rather than
 * on one momentary pose, and the samples' mutual similarity provides a per-person measurement
 * that calibrates the verification threshold.
 *
 * ### Why samples are diversified by *pose*, not by descriptor similarity
 *
 * The first version of this class rejected a candidate sample that was too similar to one
 * already accepted, measured in descriptor space. That cannot work here, and the tests proved
 * it: the descriptor is deliberately invariant to in-plane rotation, position and distance, so
 * every frame of the same person scores ~1.0 against every other, and enrolment could never
 * collect a second sample.
 *
 * What actually varies between useful captures is head *pose* — yaw and pitch — which the
 * descriptor intentionally does not normalise away. So diversity is measured there, and the
 * requirement is relaxed after [relaxAfterAttempts] rejections so that someone who simply
 * holds still still completes enrolment rather than being stuck on a spinning prompt.
 *
 * Single-use and not thread-safe: one instance per attempt, discarded on cancel.
 */
class EnrolmentSession(
    private val thresholds: FaceThresholds,
    private val staffId: String,
    private val modelVersion: Int = MODEL_VERSION,
) {
    private val evaluator = FaceQualityEvaluator(thresholds)
    private val samples = mutableListOf<FaceDescriptor>()
    private val acceptedPoses = mutableListOf<FacePose>()

    /** Consecutive frames refused for lacking pose diversity. */
    private var staleFrames: Int = 0

    val target: Int get() = thresholds.enrolmentSampleTarget

    fun progress(issue: QualityIssue = QualityIssue.OK): EnrolmentProgress = EnrolmentProgress(
        accepted = samples.size,
        target = target,
        lastIssue = issue,
        isComplete = samples.size >= target,
    )

    fun submit(observation: FaceObservation, faceCount: Int = 1): EnrolmentProgress {
        if (samples.size >= target) return progress()

        // The blur check must compare against the frame analysed a moment ago, not the last
        // one that was *accepted*. Remembering only accepted frames turned a small pose
        // adjustment between samples into a permanent "excessive motion" rejection, so
        // enrolment could never complete.
        val verdict = evaluator.evaluate(observation, faceCount)
        evaluator.remember(observation)
        if (!verdict.isAcceptable) {
            staleFrames = 0
            return progress(verdict.issue)
        }

        val pose = verdict.pose ?: return progress(QualityIssue.LANDMARK_COUNT_INVALID)
        val sufficientlyDifferent = acceptedPoses.none { it.isSimilarTo(pose, thresholds.minPoseSeparation) }
        val relaxed = staleFrames >= thresholds.relaxAfterAttempts

        if (!sufficientlyDifferent && !relaxed) {
            staleFrames++
            return progress(QualityIssue.NEED_DIFFERENT_POSE)
        }
        staleFrames = 0

        val descriptor = FaceDescriptorFactory.create(observation)
            ?: return progress(QualityIssue.LANDMARK_COUNT_INVALID)

        samples.add(descriptor)
        acceptedPoses.add(pose)
        return progress()
    }

    fun isComplete(): Boolean = samples.size >= target

    /**
     * Folds the accepted samples into a template, or returns null if enrolment is incomplete.
     *
     * Records the mean and the worst pairwise similarity between accepted samples; the worst
     * pair is what the live threshold is calibrated from.
     */
    fun buildTemplate(now: Instant): FaceTemplate? {
        if (!isComplete()) return null
        val first = samples.first()
        val descriptor = first.normalizedAverageOf(samples.drop(1))

        var sum = 0.0
        var worst = Double.POSITIVE_INFINITY
        var pairs = 0
        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                val similarity = samples[i].cosineSimilarity(samples[j])
                sum += similarity
                if (similarity < worst) worst = similarity
                pairs++
            }
        }
        val mean = if (pairs == 0) 0.0 else sum / pairs
        val minSimilarity = if (pairs == 0) 0.0 else worst

        return FaceTemplate(
            staffId = staffId,
            descriptor = descriptor.toFloatArray(),
            sampleCount = samples.size,
            createdAt = now,
            intraClassSimilarity = mean,
            intraClassMinSimilarity = minSimilarity,
            modelVersion = modelVersion,
        )
    }

    /** Diagnostic: similarity of each accepted sample to the running average. */
    fun sampleSimilarities(): List<Double> {
        if (samples.isEmpty()) return emptyList()
        val average = samples.first().normalizedAverageOf(samples.drop(1))
        return samples.map { it.cosineSimilarity(average) }
    }

    /** Diagnostic: the poses each accepted sample was captured at. */
    fun acceptedPoses(): List<FacePose> = acceptedPoses.toList()

    companion object {
        /**
         * Bump when [FaceDescriptorFactory] or [Landmarks] changes shape, so stored templates
         * from an older build are detected and re-enrolled rather than silently mis-matched.
         */
        const val MODEL_VERSION: Int = 1
    }
}

/** Two poses count as the same pose when both yaw and pitch agree closely. */
private fun FacePose.isSimilarTo(other: FacePose, tolerance: Float): Boolean {
    val yawClose = kotlin.math.abs(yawProxy - other.yawProxy) < tolerance
    val pitchClose = kotlin.math.abs(pitchProxy - other.pitchProxy) < tolerance
    return yawClose && pitchClose
}
