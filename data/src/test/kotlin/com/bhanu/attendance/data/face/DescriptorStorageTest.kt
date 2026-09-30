package com.bhanu.attendance.data.face

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class DescriptorStorageTest {

    @Test
    fun `round trips a descriptor without dropping the bytes`() {
        val original = floatArrayOf(0.1f, -0.25f, 0.5f, 1f)
        val buffer = original.toByteBuffer()
        val stored = ByteArray(buffer.remaining()).also { buffer.get(it) }

        assertThat(stored.size).isEqualTo(original.size * 4)

        val restored = ByteBuffer.wrap(stored).toLittleEndianFloats(stored.size / 4)
        assertThat(restored.toList()).containsExactlyElementsIn(original.toList()).inOrder()
    }
}
