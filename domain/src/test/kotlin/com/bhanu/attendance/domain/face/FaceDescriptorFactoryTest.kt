package com.bhanu.attendance.domain.face

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class FaceDescriptorFactoryTest {

    private fun descriptorFor(observation: FaceObservation): FaceDescriptor {
        val descriptor = FaceDescriptorFactory.create(observation)
        assertThat(descriptor).isNotNull()
        return descriptor!!
    }

    @Test
    fun `produces a descriptor of the expected dimension`() {
        val descriptor = descriptorFor(SyntheticFaces.create())
        // 478 landmarks x (x, y, z)
        assertThat(descriptor.dimension).isEqualTo(SyntheticFaces.LANDMARK_COUNT * 3)
    }

    @Test
    fun `returns null when the landmark cloud is incomplete`() {
        val truncated = SyntheticFaces.create().landmarks.take(100)
        assertThat(FaceDescriptorFactory.create(FaceObservation(truncated, 1080, 1920, 120f))).isNull()
    }

    @Test
    fun `returns null when the eye corners coincide, leaving no usable basis`() {
        val points = SyntheticFaces.create().landmarks.toMutableList()
        // Collapse both eye corners onto the same point: the x-axis degenerates.
        points[Landmarks.LEFT_EYE_OUTER_CORNER] = points[Landmarks.RIGHT_EYE_OUTER_CORNER]
        assertThat(FaceDescriptorFactory.create(FaceObservation(points, 1080, 1920, 120f))).isNull()
    }

    @Test
    fun `is invariant to where the face sits in the frame`() {
        val centred = descriptorFor(SyntheticFaces.create())
        val cornered = descriptorFor(
            SyntheticFaces.create(translateX = 0.31f, translateY = -0.22f)
        )
        assertThat(centred.cosineSimilarity(cornered)).isGreaterThan(0.999)
    }

    @Test
    fun `is invariant to how far the face is from the camera`() {
        val near = descriptorFor(SyntheticFaces.create(scale = 1.0f))
        val far = descriptorFor(SyntheticFaces.create(scale = 0.4f))
        assertThat(near.cosineSimilarity(far)).isGreaterThan(0.999)
    }

    @Test
    fun `is invariant to in-plane rotation`() {
        val upright = descriptorFor(SyntheticFaces.create(rollShiftDegrees = 0f))
        val rolled = descriptorFor(SyntheticFaces.create(rollShiftDegrees = 25f))
        assertThat(upright.cosineSimilarity(rolled)).isGreaterThan(0.99)
    }

    @Test
    fun `is unit length`() {
        val descriptor = descriptorFor(SyntheticFaces.create())
        val magnitude = kotlin.math.sqrt(descriptor.toFloatArray().sumOf { (it * it).toDouble() })
        assertThat(abs(magnitude - 1.0)).isLessThan(1e-4)
    }

    @Test
    fun `a different face scores lower than the same face`() {
        val enrolled = descriptorFor(SyntheticFaces.create(identitySeed = 1))
        val samePerson = descriptorFor(SyntheticFaces.create(identitySeed = 1, translateX = 0.2f, scale = 0.8f, noise = 0.004f, noiseSeed = 5))
        val differentPerson = descriptorFor(SyntheticFaces.create(identitySeed = 99))

        val sameScore = enrolled.cosineSimilarity(samePerson)
        val differentScore = enrolled.cosineSimilarity(differentPerson)
        assertThat(sameScore).isGreaterThan(differentScore)
        // A real margin, not just "a is above b" by a rounding error.
        assertThat(sameScore - differentScore).isGreaterThan(0.02)
    }

    @Test
    fun `identical faces score one`() {
        val a = descriptorFor(SyntheticFaces.create(identitySeed = 3))
        val b = descriptorFor(SyntheticFaces.create(identitySeed = 3))
        assertThat(a.cosineSimilarity(b)).isGreaterThan(0.9999)
    }
}
