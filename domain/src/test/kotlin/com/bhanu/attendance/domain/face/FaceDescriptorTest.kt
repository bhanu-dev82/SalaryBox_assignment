package com.bhanu.attendance.domain.face

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceDescriptorTest {

    @Test
    fun `of normalises the vector`() {
        val descriptor = FaceDescriptor.of(floatArrayOf(3f, 4f))
        assertThat(descriptor[0]).isWithin(1e-5f).of(0.6f)
        assertThat(descriptor[1]).isWithin(1e-5f).of(0.8f)
    }

    @Test
    fun `of rejects an empty vector`() {
        val error = runCatching { FaceDescriptor.of(floatArrayOf()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a degenerate zero vector stays finite rather than producing NaN`() {
        // The guard in l2Normalize exists so one bad frame cannot poison every later
        // comparison with NaN.
        val descriptor = FaceDescriptor.of(floatArrayOf(0f, 0f, 0f))
        val other = FaceDescriptor.of(floatArrayOf(1f, 0f, 0f))
        assertThat(descriptor.cosineSimilarity(other).isNaN()).isFalse()
    }

    @Test
    fun `toFloatArray returns a defensive copy`() {
        val original = floatArrayOf(1f, 0f, 0f)
        val descriptor = FaceDescriptor.of(original)
        val copy = descriptor.toFloatArray()
        copy[0] = 99f
        assertThat(descriptor[0]).isWithin(1e-5f).of(1f)
    }

    @Test
    fun `dot requires matching dimensions`() {
        val a = FaceDescriptor.of(floatArrayOf(1f, 0f))
        val b = FaceDescriptor.of(floatArrayOf(1f, 0f, 0f))
        assertThat(runCatching { a.dot(b) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `normalizedAverageOf stays unit length`() {
        val a = FaceDescriptor.of(floatArrayOf(1f, 2f, 3f))
        val b = FaceDescriptor.of(floatArrayOf(3f, 2f, 1f))
        val average = a.normalizedAverageOf(listOf(b))
        val magnitude = kotlin.math.sqrt(average.toFloatArray().sumOf { (it * it).toDouble() })
        assertThat(kotlin.math.abs(magnitude - 1.0)).isLessThan(1e-4)
    }

    @Test
    fun `equals compares content, not array identity`() {
        val a = FaceDescriptor.of(floatArrayOf(0.6f, 0.8f))
        val b = FaceDescriptor.of(floatArrayOf(3f, 4f))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
