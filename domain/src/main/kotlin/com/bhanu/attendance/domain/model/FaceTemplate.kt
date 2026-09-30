package com.bhanu.attendance.domain.model

import java.time.Instant

/**
 * The enrolled face for one staff member.
 *
 * This is biometric data. It never leaves the device, is stored in a separate table with
 * restricted access, and is excluded from backups. See docs/DECISIONS.md.
 *
 * [descriptor] is a `FloatArray`, so `equals`/`hashCode` are overridden for content
 * comparison — the generated ones would compare array identity.
 */
class FaceTemplate(
    val staffId: String,
    val descriptor: FloatArray,
    val sampleCount: Int,
    val createdAt: Instant,
    /**
     * Mean pairwise similarity among the enrolment samples. Diagnostic only.
     *
     * Calibration uses [intraClassMinSimilarity] instead: see the note there.
     */
    val intraClassSimilarity: Double,
    /**
     * The *worst* pairwise similarity among the enrolment samples.
     *
     * This is the number the verification threshold is derived from, because it is the
     * strongest evidence available that the stored template genuinely matches every frame the
     * employee agreed to enrol. Calibrating on the mean would hide a single badly-matched
     * sample behind four good ones and produce a threshold the template cannot meet.
     */
    val intraClassMinSimilarity: Double,
    val modelVersion: Int,
) {
    val dimension: Int get() = descriptor.size

    fun descriptorCopy(): FloatArray = descriptor.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceTemplate) return false
        return staffId == other.staffId &&
            descriptor.contentEquals(other.descriptor) &&
            sampleCount == other.sampleCount &&
            createdAt == other.createdAt &&
            intraClassSimilarity == other.intraClassSimilarity &&
            intraClassMinSimilarity == other.intraClassMinSimilarity &&
            modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = staffId.hashCode()
        result = 31 * result + descriptor.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + intraClassSimilarity.hashCode()
        result = 31 * result + intraClassMinSimilarity.hashCode()
        result = 31 * result + modelVersion
        return result
    }

    override fun toString(): String =
        "FaceTemplate(staffId=$staffId, dimension=$dimension, sampleCount=$sampleCount, " +
            "modelVersion=$modelVersion)"
}
