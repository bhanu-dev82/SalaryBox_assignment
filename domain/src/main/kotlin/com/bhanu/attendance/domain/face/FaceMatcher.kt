package com.bhanu.attendance.domain.face

import com.bhanu.attendance.domain.model.FaceTemplate

/**
 * Compares a live descriptor against an enrolled template.
 *
 * Because both descriptors are L2-normalised, similarity is cosine similarity, so 1.0 means
 * identical geometry and the score is naturally bounded.
 */
class FaceMatcher(private val thresholds: FaceThresholds) {

    fun score(candidate: FaceDescriptor, template: FaceDescriptor): Double {
        require(candidate.dimension == template.dimension) {
            "descriptor dimension mismatch: ${candidate.dimension} vs ${template.dimension}"
        }
        return candidate.cosineSimilarity(template)
    }

    /** Scores a candidate against a stored template's raw descriptor vector. */
    fun score(candidate: FaceDescriptor, template: FaceTemplate): Double =
        score(candidate, FaceDescriptor.of(template.descriptorCopy()))

    fun matches(candidate: FaceDescriptor, template: FaceTemplate, minSimilarity: Double): Boolean =
        score(candidate, template) >= minSimilarity

    /**
     * The threshold in force for a given template.
     *
     * A template records how consistent its own enrolment samples were; that is a far better
     * prior than a hardcoded constant, and it stops a sloppy enrolment from producing a
     * template that can never be matched. The admin's configured floor always wins as a
     * lower bound.
     */
    fun thresholdFor(template: FaceTemplate, configuredFloor: Double): Double =
        FaceThresholds.calibrate(template.intraClassMinSimilarity, configuredFloor)
}
