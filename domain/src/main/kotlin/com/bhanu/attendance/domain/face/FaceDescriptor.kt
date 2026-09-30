package com.bhanu.attendance.domain.face

import kotlin.math.sqrt

/**
 * A position- and scale-normalised, rotation-aligned face embedding.
 *
 * Immutable by convention: [values] is never exposed directly, only copied out, so a
 * descriptor handed to the matcher cannot be mutated behind its back.
 */
class FaceDescriptor private constructor(private val values: FloatArray) {

    val dimension: Int get() = values.size

    operator fun get(index: Int): Float = values[index]

    fun toFloatArray(): FloatArray = values.copyOf()

    fun dot(other: FaceDescriptor): Double {
        require(other.dimension == dimension) {
            "dimension mismatch: ${dimension} vs ${other.dimension}"
        }
        var sum = 0.0
        for (i in values.indices) sum += values[i] * other.values[i]
        return sum
    }

    /**
     * Cosine similarity in -1..1. Both descriptors are L2-normalised at construction, so
     * this is a plain dot product.
     */
    fun cosineSimilarity(other: FaceDescriptor): Double = dot(other).coerceIn(-1.0, 1.0)

    /** Euclidean distance in the unit sphere. */
    fun distance(other: FaceDescriptor): Double {
        require(other.dimension == dimension) { "dimension mismatch" }
        var sum = 0.0
        for (i in values.indices) {
            val d = (values[i] - other.values[i]).toDouble()
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * Element-wise mean of this and [others], re-normalised to unit length.
     *
     * Averaging enrolment samples reduces the variance of the stored template, which is why
     * enrolment captures more than one frame.
     */
    fun normalizedAverageOf(others: List<FaceDescriptor>): FaceDescriptor {
        val all = if (others.isEmpty()) listOf(this) else listOf(this) + others
        val out = FloatArray(dimension)
        for (d in all) {
            require(d.dimension == dimension) { "dimension mismatch" }
            for (i in 0 until dimension) out[i] += d.values[i]
        }
        return FaceDescriptor(l2Normalize(out))
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is FaceDescriptor && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "FaceDescriptor(dimension=$dimension)"

    companion object {
        /** Wraps an already-built vector, normalising it. Used by tests and by the data layer. */
        fun of(values: FloatArray): FaceDescriptor {
            require(values.isNotEmpty()) { "descriptor must not be empty" }
            return FaceDescriptor(l2Normalize(values))
        }

        fun l2Normalize(values: FloatArray): FloatArray {
            var sumSquares = 0.0
            for (v in values) sumSquares += v * v
            val magnitude = sqrt(sumSquares)
            // A degenerate (all-zero) vector cannot be normalised; returning it as-is keeps
            // the similarity 0 rather than producing NaN that would poison comparisons.
            if (magnitude < 1e-9) return values
            val out = FloatArray(values.size)
            for (i in values.indices) out[i] = (values[i] / magnitude).toFloat()
            return out
        }
    }
}
