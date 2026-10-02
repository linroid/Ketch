package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text styles. Inter for text, Inter Display for titles and large numerals, JetBrains Mono for
 * URLs, paths and hashes, never for speeds or sizes. Every `numeral*` style uses tabular
 * figures, so digits do not jitter as progress ticks.
 *
 * @property pageTitle desktop page header.
 * @property largeTitle phone large title, onboarding and empty-state titles.
 * @property titleL dialogs, the intake sheet and the Devices page title.
 * @property titleM inspector file name, device-card name and setting group titles.
 * @property bodyStrong list-row name.
 * @property body body text.
 * @property bodyS secondary body text and inspector values.
 * @property cell table cells.
 * @property cellStrong the table's Name column.
 * @property caption row meta, hints and reasons.
 * @property label buttons, sidebar items and tabs.
 * @property labelS chips, pills and menu shortcut hints.
 * @property eyebrow group headers, the table header and card labels; uppercase the text with
 *   [eyebrowText].
 * @property numeralXL device-card speed and the phone Pulse sheet.
 * @property numeralL inspector speed.
 * @property numeral row speed, size, ETA and percentage, and the Pulse bar speed.
 * @property numeralS tab counts, sidebar live lines and the rail readout.
 * @property mono URLs, paths, hashes, task IDs and cURL.
 * @property monoS lane index and hash snippets.
 */
@Immutable
data class KetchTypography(
  val pageTitle: TextStyle,
  val largeTitle: TextStyle,
  val titleL: TextStyle,
  val titleM: TextStyle,
  val bodyStrong: TextStyle,
  val body: TextStyle,
  val bodyS: TextStyle,
  val cell: TextStyle,
  val cellStrong: TextStyle,
  val caption: TextStyle,
  val label: TextStyle,
  val labelS: TextStyle,
  val eyebrow: TextStyle,
  val numeralXL: TextStyle,
  val numeralL: TextStyle,
  val numeral: TextStyle,
  val numeralS: TextStyle,
  val mono: TextStyle,
  val monoS: TextStyle,
)

/**
 * [text] as an eyebrow label shows it, in uppercase. Text styles cannot change case, so
 * labels set in [KetchTypography.eyebrow] go through this instead of uppercasing by hand.
 */
fun eyebrowText(text: String): String = text.uppercase()

/**
 * Builds the text styles from [sans] (Inter), [display] (Inter Display) and [mono]. At
 * [KetchDensity.Comfortable] row names and captions are a step larger.
 */
fun ketchTypography(
  sans: FontFamily = FontFamily.SansSerif,
  display: FontFamily = sans,
  mono: FontFamily = FontFamily.Monospace,
  density: KetchDensity = KetchDensity.Compact,
): KetchTypography {
  val comfortable = density == KetchDensity.Comfortable
  fun style(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    tracking: TextUnit = 0.sp,
    tabular: Boolean = false,
  ) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking,
    fontFeatureSettings = if (tabular) "tnum" else null,
  )
  return KetchTypography(
    pageTitle = style(display, FontWeight.SemiBold, 22, 28, (-0.3).sp),
    largeTitle = style(display, FontWeight.Bold, 28, 34, (-0.5).sp),
    titleL = style(sans, FontWeight.SemiBold, 20, 26, (-0.3).sp),
    titleM = style(sans, FontWeight.SemiBold, 15, 20, (-0.1).sp),
    bodyStrong = if (comfortable) {
      style(sans, FontWeight.Medium, 15, 20)
    } else {
      style(sans, FontWeight.Medium, 14, 20)
    },
    body = style(sans, FontWeight.Normal, 14, 20),
    bodyS = style(sans, FontWeight.Normal, 13, 18),
    cell = style(sans, FontWeight.Normal, 13, 18),
    cellStrong = style(sans, FontWeight.Medium, 13, 18),
    caption = if (comfortable) {
      style(sans, FontWeight.Normal, 13, 18)
    } else {
      style(sans, FontWeight.Normal, 12, 16)
    },
    label = style(sans, FontWeight.Medium, 13, 16),
    labelS = style(sans, FontWeight.Medium, 12, 16),
    eyebrow = style(sans, FontWeight.SemiBold, 11, 14, 0.8.sp),
    numeralXL = style(display, FontWeight.SemiBold, 40, 44, (-1).sp, tabular = true),
    numeralL = style(display, FontWeight.SemiBold, 20, 24, (-0.3).sp, tabular = true),
    numeral = style(sans, FontWeight.Medium, 13, 18, tabular = true),
    numeralS = style(sans, FontWeight.Medium, 11, 14, tabular = true),
    mono = style(mono, FontWeight.Normal, 12, 18),
    monoS = style(mono, FontWeight.Normal, 11, 16),
  )
}
