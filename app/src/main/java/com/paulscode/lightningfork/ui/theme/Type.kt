package com.paulscode.lightningfork.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.paulscode.lightningfork.R

/** Inter, as the dashboard uses it. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semi_bold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extra_bold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val AppTypography = Typography(
    displayLarge = style(44, FontWeight.Bold, 50, -0.02),
    displayMedium = style(36, FontWeight.Bold, 42, -0.02),
    displaySmall = style(30, FontWeight.Bold, 36, -0.015),
    headlineLarge = style(26, FontWeight.Bold, 32, -0.01),
    headlineMedium = style(22, FontWeight.Bold, 28, -0.01),
    headlineSmall = style(20, FontWeight.SemiBold, 26),
    titleLarge = style(18, FontWeight.SemiBold, 24),
    titleMedium = style(16, FontWeight.SemiBold, 22),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 16),
    labelLarge = style(15, FontWeight.SemiBold, 20, 0.005),
    labelMedium = style(13, FontWeight.Medium, 18, 0.01),
    labelSmall = style(11, FontWeight.SemiBold, 14, 0.06),
)
