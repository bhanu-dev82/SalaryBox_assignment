package com.bhanu.attendance.domain.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ValidatorsTest {

    @Test
    fun `a reasonable pin is accepted`() {
        assertThat(Validators.validatePin("4821").isValid).isTrue()
    }

    @Test
    fun `an empty pin is rejected`() {
        val result = Validators.validatePin("") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.EMPTY)
    }

    @Test
    fun `a short pin is rejected`() {
        val result = Validators.validatePin("12") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.TOO_SHORT)
    }

    @Test
    fun `a long pin is rejected`() {
        val result = Validators.validatePin("1234567890123") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.TOO_LONG)
    }

    @Test
    fun `a non-numeric pin is rejected`() {
        val result = Validators.validatePin("12a4") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.INVALID_CHARS)
    }

    @Test
    fun `a repeated-digit pin is rejected as too guessable`() {
        val result = Validators.validatePin("1111") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.WEAK)
    }

    @Test
    fun `a sequence pin is rejected as too guessable`() {
        assertThat((Validators.validatePin("1234") as ValidationResult.Invalid).reason)
            .isEqualTo(ValidationResult.Reason.WEAK)
    }

    @Test
    fun `a good name is accepted and whitespace collapsed`() {
        assertThat(Validators.validateName("Ravi Kumar").isValid).isTrue()
        assertThat(Validators.normaliseName("  Ravi   Kumar ")).isEqualTo("Ravi Kumar")
    }

    @Test
    fun `a single-character name is rejected`() {
        assertThat((Validators.validateName("R") as ValidationResult.Invalid).reason)
            .isEqualTo(ValidationResult.Reason.TOO_SHORT)
    }

    @Test
    fun `a name with control characters is rejected`() {
        assertThat(Validators.validateName("Ravi").isValid).isFalse()
    }

    @Test
    fun `employee ids are normalised to upper case`() {
        assertThat(Validators.normaliseEmployeeId("  emp-001 ")).isEqualTo("EMP-001")
    }

    @Test
    fun `a valid employee id is accepted`() {
        assertThat(Validators.validateEmployeeId("EMP-001").isValid).isTrue()
        assertThat(Validators.validateEmployeeId("emp_42").isValid).isTrue()
    }

    @Test
    fun `an employee id with punctuation is rejected`() {
        val result = Validators.validateEmployeeId("EMP 001!") as ValidationResult.Invalid
        assertThat(result.reason).isEqualTo(ValidationResult.Reason.INVALID_CHARS)
    }
}
