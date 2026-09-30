package com.bhanu.attendance.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale.
 *
 * Two deliberate choices:
 *
 * 1. All sizes are in `sp`, never `dp`, so they respond to the system font-size setting.
 * 2. Line heights are generous and use `LineHeightStyle` with a trim so that at 200%+ font
 *    scale text still has breathing room instead of the tight, clipped look that produces
 *    overlap in dense list rows.
 */
private val trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = trim,
)

val BhanuTypography = Typography(
    displaySmall = style(34, 42, FontWeight.Normal, (-0.25)),
    headlineLarge = style(30, 38, FontWeight.Normal),
    headlineMedium = style(26, 34, FontWeight.SemiBold),
    headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold, 0.15),
    titleSmall = style(14, 20, FontWeight.Medium, 0.1),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.5),
    bodyMedium = style(14, 21, FontWeight.Normal, 0.25),
    bodySmall = style(12, 18, FontWeight.Normal, 0.4),
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.5),
    labelSmall = style(11, 16, FontWeight.Medium, 0.5),
)
