package com.bhanu.attendance.domain.outcome

/**
 * A closed set of failures the app knows how to explain to a user.
 *
 * This exists instead of throwing, and instead of a bare `Result`, because every one of these
 * is a case the UI must render as a specific, actionable message. An untyped
 * `runCatching { }` tends to surface as a generic "something went wrong" — which is precisely
 * the complaint users leave on the real SalaryBox Play Store listing. Naming the failure
 * forces the UI to say something useful, and makes the error path exhaustively testable.
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {

    // --- authentication ---
    data class InvalidCredentials(val roleHint: String) :
        AppError("Incorrect $roleHint or PIN")

    data class AccountLocked(val reason: String) : AppError("Account unavailable: $reason")

    // --- staff management ---
    data object DuplicateEmployeeId : AppError("That employee ID is already in use")

    data class StaffNotFound(val employeeId: String) : AppError("No staff member with ID $employeeId")

    data class StaffInactive(val name: String) : AppError("$name's account is deactivated")

    data class Validation(val field: String, val detail: String) :
        AppError("$field: $detail")

    // --- face pipeline ---
    data object NotEnrolled : AppError(
        "Your face is not enrolled yet. Ask an admin to enrol it before marking attendance."
    )

    data object FaceEngineUnavailable : AppError(
        "Face recognition is unavailable on this device."
    )

    data object FaceModelMissing : AppError(
        "The face model failed to load. Reinstall the app and try again."
    )

    data class NoCamera(val detail: String) : AppError("Camera unavailable: $detail")

    data object PermissionDeniedCamera : AppError(
        "Camera permission is required to verify your face."
    )

    data object PermissionDeniedLocation : AppError(
        "Location permission was denied. Attendance will be recorded without a location."
    )

    // --- attendance ---
    data object AlreadyPunchedIn : AppError("You have already punched in today")

    data object AlreadyPunchedOut : AppError("You have already punched out today")

    data object NotPunchedIn : AppError("Punch in first, then punch out")

    data object FaceDidNotMatch : AppError(
        "Your face did not match the enrolled face. Try again in better light, facing the camera."
    )

    data object StorageFull : AppError("Device storage is full. Free some space and try again.")

    data object SelfieWriteFailed : AppError("Could not save the photo. Please try again.")

    // --- catch-all ---
    data class Unexpected(val detail: String, override val cause: Throwable?) :
        AppError("Unexpected problem: $detail", cause)
}

/**
 * Success-or-typed-failure. Deliberately mirrors the shape of `kotlin.Result` but is a
 * sealed class so it can be `when`-matched exhaustively in the UI and in tests.
 */
sealed interface Outcome<out T> {

    data class Success<out T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: AppError): Outcome<Nothing> = Failure(error)
    }
}

/** Runs [block], converting any unexpected throwable into [AppError.Unexpected]. */
inline fun <T> runCatchingOutcome(block: () -> T): Outcome<T> = try {
    Outcome.success(block())
} catch (t: Throwable) {
    // CancellationException must never be swallowed — it is control flow, not an error.
    if (t is kotlinx.coroutines.CancellationException) throw t
    Outcome.failure(AppError.Unexpected(t.message ?: t::class.java.simpleName, t))
}
