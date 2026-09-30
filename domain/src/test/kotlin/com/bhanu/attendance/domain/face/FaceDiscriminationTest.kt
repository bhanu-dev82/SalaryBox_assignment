package com.bhanu.attendance.domain.face

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The test that matters most for this app.
 *
 * A descriptor that is merely "self-consistent" is useless; it has to *separate* people. This
 * asserts a clean margin between same-person captures and different-person captures, using a
 * synthetic cohort so it runs in milliseconds on every commit.
 *
 * This test was added after an earlier fixture version reported a separation of ~0.004 with
 * overlap — which is precisely the failure mode that would have shipped a face check that
 * accepted everybody.
 */
class FaceDiscriminationTest {

    private fun descriptorFor(
        identitySeed: Int,
        noise: Float = 0f,
        noiseSeed: Int = 0,
    ) = FaceDescriptorFactory.create(
        SyntheticFaces.create(identitySeed = identitySeed, noise = noise, noiseSeed = noiseSeed)
    )!!

    private val cohort = listOf(1, 99, 1234, 555, 7777, 31337, 42, 90210)

    /**
     * Feeds an [EnrolmentSession] until it has accepted [target] samples.
     *
     * Models a realistic capture: a stream of frames at a steady 30fps with the head drifting
     * slowly through an arc, so consecutive frames are close together (as they are in reality)
     * while successive accepted samples come from genuinely different angles. Jumping straight
     * between poses would be rejected by the motion gate — correctly, because that is what a
     * blurred or re-framed frame looks like.
     */
    private fun collectSamples(session: EnrolmentSession, target: Int) {
        val step = 1
        var frame = 0
        while (session.progress().accepted < target && frame < 4000) {
            // Drift roll from -7 to +7 degrees in 0.5-degree steps, then reverse.
            val roll = -7f + ((frame * step) % 28) * 0.5f
            session.submit(
                SyntheticFaces.create(
                    identitySeed = 1,
                    noise = 0.0015f,
                    noiseSeed = frame,
                    rollShiftDegrees = roll,
                )
            )
            frame++
        }
        assertThat(session.progress().accepted).isEqualTo(target)
    }

    @Test
    fun `same person across varied captures scores higher than different people`() {
        val intra = mutableListOf<Double>()
        for (person in cohort) {
            val reference = descriptorFor(person)
            intra += reference.cosineSimilarity(descriptorFor(person, noise = 0.006f, noiseSeed = 3))
            intra += reference.cosineSimilarity(descriptorFor(person, noise = 0.006f, noiseSeed = 9))
            intra += reference.cosineSimilarity(
                FaceDescriptorFactory.create(
                    SyntheticFaces.create(
                        identitySeed = person,
                        translateX = 0.2f,
                        scale = 0.7f,
                        rollShiftDegrees = 8f,
                    )
                )!!
            )
        }

        val inter = mutableListOf<Double>()
        val descriptors = cohort.map { descriptorFor(it) }
        for (i in descriptors.indices) {
            for (j in i + 1 until descriptors.size) {
                inter += descriptors[i].cosineSimilarity(descriptors[j])
            }
        }

        val worstIntra = intra.min()
        val bestInter = inter.max()

        // No overlap: every same-person capture beats every different-person capture.
        assertThat(worstIntra).isGreaterThan(bestInter)

        // And the margin is wide enough to be a usable decision boundary, not a rounding error.
        assertThat(worstIntra - bestInter).isGreaterThan(0.01)
    }

    @Test
    fun `a calibrated threshold from enrolment separates the cohort`() {
        val thresholds = FaceThresholds()

        // Enrol one person properly and take the threshold the app would derive.
        val enrolment = EnrolmentSession(thresholds, staffId = "s1")
        collectSamples(enrolment, thresholds.enrolmentSampleTarget)
        val template = enrolment.buildTemplate(java.time.Instant.EPOCH)
        assertThat(template).isNotNull()

        val matcher = FaceMatcher(thresholds)
        val threshold = matcher.thresholdFor(template!!, thresholds.defaultMinSimilarity)

        // The enrolled person matches, several other people do not.
        for (attempt in 0..2) {
            val sameScore = matcher.score(
                descriptorFor(1, noise = 0.005f, noiseSeed = 100 + attempt), template
            )
            assertThat(sameScore).isAtLeast(threshold)
        }
        for (other in listOf(99, 1234, 7777)) {
            val otherScore = matcher.score(descriptorFor(other), template)
            assertThat(otherScore).isLessThan(threshold)
        }
    }

    @Test
    fun `calibration never produces a threshold below the configured floor`() {
        // A pathological enrolment (near-zero self-similarity) must not yield a useless threshold.
        val calibrated = FaceThresholds.calibrate(intraClassMinSimilarity = 0.30, configuredFloor = 0.95)
        assertThat(calibrated).isAtLeast(0.95)
    }

    @Test
    fun `calibration is bounded at the top of the usable range`() {
        // A pathological perfect enrolment must not yield a threshold of 1.0, which nothing
        // live could ever satisfy.
        val calibrated = FaceThresholds.calibrate(intraClassMinSimilarity = 1.0, configuredFloor = 0.95)
        assertThat(calibrated).isAtMost(FaceThresholds.CALIBRATED_RANGE.endInclusive)
        assertThat(calibrated).isLessThan(1.0)
    }

    @Test
    fun `calibration falls back to the floor for a non-finite enrolment`() {
        assertThat(FaceThresholds.calibrate(Double.NaN, 0.95)).isEqualTo(0.95)
    }
}
