package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class KetchTypography(
  // Display / section headers
  val displayLarge: TextStyle,
  val displayMedium: TextStyle,
  val displaySmall: TextStyle,

  // Body
  val bodyLarge: TextStyle,
  val bodyMedium: TextStyle,
  val bodySmall: TextStyle,

  // Labels
  val labelLarge: TextStyle,
  val labelMedium: TextStyle,
  val labelSmall: TextStyle,

  // Monospace — for sizes / speeds / URLs
  val monoMedium: TextStyle,
  val monoSmall: TextStyle,
  val monoXSmall: TextStyle,
)

// Platform-safe defaults. Wire in bundled Inter + JetBrains Mono resources later.
val KetchSans: FontFamily = FontFamily.SansSerif
val KetchMono: FontFamily = FontFamily.Monospace

fun ketchTypography(
  sans: FontFamily = KetchSans,
  mono: FontFamily = KetchMono,
): KetchTypography = KetchTypography(
  displayLarge = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
  ),
  displayMedium = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.25).sp,
  ),
  displaySmall = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Normal,
    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Normal,
    fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Normal,
    fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Medium,
    fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Medium,
    fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = sans, fontWeight = FontWeight.Medium,
    fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp,
  ),
  monoMedium = TextStyle(
    fontFamily = mono, fontWeight = FontWeight.Medium,
    fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.2).sp,
  ),
  monoSmall = TextStyle(
    fontFamily = mono, fontWeight = FontWeight.Normal,
    fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
  ),
  monoXSmall = TextStyle(
    fontFamily = mono, fontWeight = FontWeight.Medium,
    fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp,
  ),
)
