package com.bhanu.attendance.domain.outcome

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class OutcomeTest {

    @Test
    fun `map transforms a success`() {
        val result: Outcome<Int> = Outcome.success(2)
        assertThat(result.map { it * 3 }).isEqualTo(Outcome.success(6))
    }

    @Test
    fun `map leaves a failure untouched`() {
        val result: Outcome<Int> = Outcome.failure(AppError.NotEnrolled)
        assertThat(result.map { it * 3 }).isEqualTo(Outcome.failure(AppError.NotEnrolled))
    }

    @Test
    fun `getOrNull and errorOrNull expose the right half`() {
        val ok: Outcome<Int> = Outcome.success(7)
        val bad: Outcome<Int> = Outcome.failure(AppError.StorageFull)
        assertThat(ok.getOrNull()).isEqualTo(7)
        assertThat(ok.errorOrNull()).isNull()
        assertThat(bad.getOrNull()).isNull()
        assertThat(bad.errorOrNull()).isEqualTo(AppError.StorageFull)
    }

    @Test
    fun `runCatchingOutcome converts a success`() {
        assertThat(runCatchingOutcome { 42 }.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `runCatchingOutcome converts a throw into Unexpected`() {
        val error = runCatchingOutcome { throw IllegalStateException("boom") }.errorOrNull()
        assertThat(error).isInstanceOf(AppError.Unexpected::class.java)
        assertThat((error as AppError.Unexpected).detail).isEqualTo("boom")
    }

    @Test
    fun `runCatchingOutcome rethrows CancellationException rather than swallowing it`() {
        // Cancellation is control flow, not an error. Converting it into a failure value would
        // break structured concurrency and hide a cancelled job.
        var caught: Throwable? = null
        try {
            runCatchingOutcome { throw CancellationException("cancelled") }
        } catch (t: Throwable) {
            caught = t
        }
        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }
}
