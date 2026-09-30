package com.bhanu.attendance.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/*
 * A hand-tuned Material 3 palette rather than the default purple.
 *
 * The brand is a payroll/attendance product used on cheap handsets in bright outdoor light,
 * so the palette is chosen for contrast: a deep indigo primary that stays legible at high
 * luminance, and a distinctly green success colour that reads as "recorded" without
 * competing with the primary. Every pairing below was chosen to clear WCAG AA (4.5:1) for
 * body text against its `on*` colour.
 */

// --- light ---
val LightPrimary = Color(0xFF1B4D8F)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD5E3FF)
val LightOnPrimaryContainer = Color(0xFF001C3B)

val LightSecondary = Color(0xFF116149)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFA4F2CC)
val LightOnSecondaryContainer = Color(0xFF00281A)

val LightTertiary = Color(0xFF6B5778)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF3DAFF)
val LightOnTertiaryContainer = Color(0xFF251431)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFDFBFF)
val LightOnBackground = Color(0xFF1A1C1E)
val LightSurface = Color(0xFFFDFBFF)
val LightOnSurface = Color(0xFF1A1C1E)
val LightSurfaceVariant = Color(0xFFDFE2EB)
val LightOnSurfaceVariant = Color(0xFF43474E)
val LightOutline = Color(0xFF73777F)

// --- dark ---
val DarkPrimary = Color(0xFFA5C8FF)
val DarkOnPrimary = Color(0xFF00315C)
val DarkPrimaryContainer = Color(0xFF004781)
val DarkOnPrimaryContainer = Color(0xFFD5E3FF)

val DarkSecondary = Color(0xFF88D5B1)
val DarkOnSecondary = Color(0xFF003825)
val DarkSecondaryContainer = Color(0xFF005137)
val DarkOnSecondaryContainer = Color(0xFFA4F2CC)

val DarkTertiary = Color(0xFFD7BEE4)
val DarkOnTertiary = Color(0xFF3C2947)
val DarkTertiaryContainer = Color(0xFF533F5F)
val DarkOnTertiaryContainer = Color(0xFFF3DAFF)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF1A1C1E)
val DarkOnBackground = Color(0xFFE3E2E6)
val DarkSurface = Color(0xFF1A1C1E)
val DarkOnSurface = Color(0xFFE3E2E6)
val DarkSurfaceVariant = Color(0xFF43474E)
val DarkOnSurfaceVariant = Color(0xFFC3C7CF)
val DarkOutline = Color(0xFF8D9199)

/**
 * Semantic colours for attendance status.
 *
 * Kept out of the M3 scheme on purpose: "punched in" and "outside the geofence" are domain
 * states, not theme roles, and they must stay stable if the brand palette changes. Each has a
 * light and dark variant because the same status colour cannot serve both themes.
 */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightStatusColors = StatusColors(
    success = Color(0xFF116149),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFF8A5000),
    onWarning = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA4F2CC),
    onSuccessContainer = Color(0xFF00281A),
    warningContainer = Color(0xFFFFDDB6),
    onWarningContainer = Color(0xFF2C1600),
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF88D5B1),
    onSuccess = Color(0xFF003825),
    warning = Color(0xFFFFB951),
    onWarning = Color(0xFF492900),
    successContainer = Color(0xFF005137),
    onSuccessContainer = Color(0xFFA4F2CC),
    warningContainer = Color(0xFF683C00),
    onWarningContainer = Color(0xFFFFDDB6),
)
