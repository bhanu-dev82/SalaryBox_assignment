package com.bhanu.attendance.domain.validation

/** Field-level validation results, kept in the domain so the rules are unit-testable. */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reason: Reason) : ValidationResult

    enum class Reason {
        EMPTY, TOO_SHORT, TOO_LONG, INVALID_CHARS, NOT_NUMERIC, WEAK, MALFORMED,
    }

    val isValid: Boolean get() = this is Valid
}

object Validators {
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 12
    const val MIN_NAME_LENGTH = 2
    const val MAX_NAME_LENGTH = 60
    const val MIN_EMPLOYEE_ID_LENGTH = 2
    const val MAX_EMPLOYEE_ID_LENGTH = 20

    /**
     * Digits only. Deliberately not a password-strength meter: a 4-digit admin PIN on a work
     * handset is a speed bump against casual shoulder-surfing, not a defence against a
     * determined attacker. The real control is that PINs are hashed at rest and the app is
     * offline. Documented rather than pretending otherwise.
     */
    fun validatePin(pin: String): ValidationResult = when {
        pin.isBlank() -> ValidationResult.Invalid(ValidationResult.Reason.EMPTY)
        pin.length < MIN_PIN_LENGTH ->
            ValidationResult.Invalid(ValidationResult.Reason.TOO_SHORT)
        pin.length > MAX_PIN_LENGTH ->
            ValidationResult.Invalid(ValidationResult.Reason.TOO_LONG)
        !pin.all { it.isDigit() } -> ValidationResult.Invalid(ValidationResult.Reason.INVALID_CHARS)
        isTooTrivial(pin) -> ValidationResult.Invalid(ValidationResult.Reason.WEAK)
        else -> ValidationResult.Valid
    }

    private fun isTooTrivial(pin: String): Boolean =
        pin.toSet().size == 1 || pin == "1234" || pin == "4321" || pin == "0000"

    fun validateName(name: String): ValidationResult = when {
        name.isBlank() -> ValidationResult.Invalid(ValidationResult.Reason.EMPTY)
        name.trim().length < MIN_NAME_LENGTH ->
            ValidationResult.Invalid(ValidationResult.Reason.TOO_SHORT)
        name.trim().length > MAX_NAME_LENGTH ->
            ValidationResult.Invalid(ValidationResult.Reason.TOO_LONG)
        name.any { it.isISOControl() } -> ValidationResult.Invalid(ValidationResult.Reason.INVALID_CHARS)
        else -> ValidationResult.Valid
    }

    /** Employee ids are alphanumeric with `-` and `_`; normalises to upper case. */
    fun validateEmployeeId(employeeId: String): ValidationResult {
        val trimmed = employeeId.trim()
        return when {
            trimmed.isEmpty() -> ValidationResult.Invalid(ValidationResult.Reason.EMPTY)
            trimmed.length < MIN_EMPLOYEE_ID_LENGTH ->
                ValidationResult.Invalid(ValidationResult.Reason.TOO_SHORT)
            trimmed.length > MAX_EMPLOYEE_ID_LENGTH ->
                ValidationResult.Invalid(ValidationResult.Reason.TOO_LONG)
            !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' } ->
                ValidationResult.Invalid(ValidationResult.Reason.INVALID_CHARS)
            else -> ValidationResult.Valid
        }
    }

    fun normaliseEmployeeId(employeeId: String): String = employeeId.trim().uppercase()

    fun normaliseName(name: String): String = name.trim().replace(Regex("\\s+"), " ")
}
