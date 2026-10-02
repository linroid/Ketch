package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * Color tokens of the Ketch design system, for one accent in one theme.
 *
 * Build them with [lightKetchColors] or [darkKetchColors] and read them through
 * `KetchTheme.colors`. Every text-on-fill pair is at least 4.5:1 (`ThemeContrastTest`).
 *
 * @property canvas window background behind the sidebar and around the content card.
 * @property wash gradient drawn once over the [canvas], never on surfaces.
 * @property surface content card, table body and inspector card.
 * @property surfaceRaised menus, popovers, the palette, dialogs, toasts and the intake sheet.
 * @property surfaceSunken inputs, segmented tracks, lane tracks, the table header and the
 *   Pulse bar.
 * @property surfaceHover row and item hover.
 * @property surfacePressed press state.
 * @property rowSelected selected row, the accent tinted over [surface].
 * @property rowSelectedFocused selected row while the table has keyboard focus.
 * @property sidebarItemSelected translucent pill of the selected sidebar item.
 * @property sidebarItemHover translucent pill of a hovered sidebar item.
 * @property hairline 1 dp borders of the card and menus, and dividers between regions.
 * @property borderStrong borders of inputs, Secondary buttons and checkboxes.
 * @property divider row dividers.
 * @property scrim translucent layer behind dialogs and sheets.
 * @property inverseSurface tooltip and toast fill.
 * @property inverseOnSurface text on [inverseSurface].
 * @property inverseAccent action text on [inverseSurface]: the other theme's [accentText].
 * @property textPrimary names, titles and values.
 * @property textSecondary meta and labels; the only secondary text allowed on the wash.
 * @property textTertiary captions, units and shortcut hints, only on [surface] or
 *   [surfaceSunken].
 * @property textDisabled disabled controls.
 * @property accent fill of Primary buttons, selection and Downloading.
 * @property accentHover [accent] with the hover overlay.
 * @property accentSoft fill of Tonal buttons and selected chips.
 * @property accentText accent-colored text on [surface] or [accentSoft].
 * @property onAccent text on [accent], white or ink, whichever reads at 4.5:1.
 * @property dangerFill fill of destructive buttons and failure count badges, under white text.
 * @property brandEmber the two stops of the 135° ember gradient. Only the logo tile, the
 *   illustration, the completion sheen and the Add button's hover and drop fills may use it.
 * @property status colors of each download state.
 * @property lanes eight opaque lane colors: the accent ramp over [surfaceSunken].
 * @property deviceHues hues a device pennant can get, picked by [deviceHue].
 * @property isDark whether these are the dark theme's colors.
 */
@Immutable
data class KetchColors(
  val canvas: Color,
  val wash: KetchWash,
  val surface: Color,
  val surfaceRaised: Color,
  val surfaceSunken: Color,
  val surfaceHover: Color,
  val surfacePressed: Color,
  val rowSelected: Color,
  val rowSelectedFocused: Color,
  val sidebarItemSelected: Color,
  val sidebarItemHover: Color,
  val hairline: Color,
  val borderStrong: Color,
  val divider: Color,
  val scrim: Color,
  val inverseSurface: Color,
  val inverseOnSurface: Color,
  val inverseAccent: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textTertiary: Color,
  val textDisabled: Color,
  val accent: Color,
  val accentHover: Color,
  val accentSoft: Color,
  val accentText: Color,
  val onAccent: Color,
  val dangerFill: Color,
  val brandEmber: List<Color>,
  val status: KetchStatusColors,
  val lanes: List<Color>,
  val deviceHues: List<FileTypeHue>,
  val isDark: Boolean,
) {
  /** The lane ramp in [color] over [surfaceSunken], such as `status.paused.color` when paused. */
  fun laneRamp(color: Color): List<Color> = laneColors(color, surfaceSunken)

  /** Pennant hue of the device [deviceId]; the same id always gets the same hue. */
  fun deviceHue(deviceId: String): FileTypeHue {
    var hash = 0
    for (char in deviceId) hash = 31 * hash + char.code
    return deviceHues[hash.mod(deviceHues.size)]
  }
}

/**
 * The canvas wash: a 160° linear gradient through [start], [mid] and [end], plus a radial
 * [ember] glow (radius 520 dp) centered on the bottom-left corner of the sidebar.
 */
@Immutable
data class KetchWash(
  val start: Color,
  val mid: Color,
  val end: Color,
  val ember: Color,
)

/**
 * Color of one download status.
 *
 * @property color the dot and, for Failed only, the label.
 * @property soft [color] at 10% over the surface, for pills and backgrounds.
 */
@Immutable
data class KetchStatusColor(
  val color: Color,
  val soft: Color,
)

/**
 * Colors of each download status. Priority never borrows them. Stalled and throttled
 * transfers use [paused].
 */
@Immutable
data class KetchStatusColors(
  val downloading: KetchStatusColor,
  val queued: KetchStatusColor,
  val scheduled: KetchStatusColor,
  val paused: KetchStatusColor,
  val completed: KetchStatusColor,
  val failed: KetchStatusColor,
  val canceled: KetchStatusColor,
  val seeding: KetchStatusColor,
)

/** Accent palettes, chosen in Settings → General → Accent. */
enum class KetchAccent(val displayName: String) {
  Signal("Signal"),
  Harbor("Harbor"),
  Fathom("Fathom"),
  Beacon("Beacon"),
}

internal object KetchPalette {
  val Ink = Color(0xFF141A26)
  val ScrimInk = Color(0xFF0B0D12)
  val EmberStart = Color(0xFFFFB25B)
  val EmberEnd = Color(0xFFE0482B)

  /** Alphas of the accent in successive lanes. */
  val LaneAlphas = listOf(1f, 0.72f, 0.88f, 0.60f, 0.94f, 0.66f, 0.80f, 0.54f)

  val DeviceHues = listOf(
    FileTypeHue.Sky, FileTypeHue.Teal, FileTypeHue.Magenta, FileTypeHue.Lime,
    FileTypeHue.Orange, FileTypeHue.Slate, FileTypeHue.Jade, FileTypeHue.Brown,
  )

  class AccentTone(val fill: Color, val text: Color, val soft: Color)

  fun accentTone(accent: KetchAccent, dark: Boolean): AccentTone = when (accent) {
    KetchAccent.Signal -> if (dark) {
      AccentTone(Color(0xFF5563F0), Color(0xFF8E9BFF), Color(0xFF1D2140))
    } else {
      AccentTone(Color(0xFF4F5DE4), Color(0xFF3C47B7), Color(0xFFECEEFE))
    }
    KetchAccent.Harbor -> if (dark) {
      AccentTone(Color(0xFF00818D), Color(0xFF4FD1DB), Color(0xFF0D2E33))
    } else {
      AccentTone(Color(0xFF00818D), Color(0xFF006A74), Color(0xFFDDF5F7))
    }
    KetchAccent.Fathom -> if (dark) {
      AccentTone(Color(0xFF007F35), Color(0xFF5FD08A), Color(0xFF0E2C1A))
    } else {
      AccentTone(Color(0xFF007F35), Color(0xFF00692C), Color(0xFFDCF3E3))
    }
    KetchAccent.Beacon -> if (dark) {
      AccentTone(Color(0xFFC9431C), Color(0xFFFF9A6B), Color(0xFF34160D))
    } else {
      AccentTone(Color(0xFFC9431C), Color(0xFFA8370F), Color(0xFFFFE9E0))
    }
  }
}

/**
 * Hues for file-type tiles, spaced around the OKLCH wheel at a shared lightness per theme so
 * no kind looks louder than another. Each glyph keeps at least 3:1 against its own tinted
 * tile.
 */
enum class FileTypeHue(val light: Color, val dark: Color) {
  Red(Color(0xFFB63B39), Color(0xFFFF8A83)),
  Orange(Color(0xFFA75001), Color(0xFFF4975B)),
  Amber(Color(0xFF8E6201), Color(0xFFDDA64A)),
  Brown(Color(0xFF835935), Color(0xFFCBA180)),
  Lime(Color(0xFF5B7809), Color(0xFFA0BE69)),
  Green(Color(0xFF11813C), Color(0xFF72C886)),
  Jade(Color(0xFF037E65), Color(0xFF5EC7A9)),
  Teal(Color(0xFF017B80), Color(0xFF58C4CA)),
  Sky(Color(0xFF00769F), Color(0xFF58BEEE)),
  Blue(Color(0xFF3566C7), Color(0xFF86B1FF)),
  Indigo(Color(0xFF615CBF), Color(0xFFA5A7FE)),
  Violet(Color(0xFF8050B0), Color(0xFFC59AF6)),
  Magenta(Color(0xFFA34284), Color(0xFFEB8DC9)),
  Slate(Color(0xFF5E6D80), Color(0xFFA4B3C5)),
  ;

  companion object {
    /** Opacity of the hue behind the glyph on a file-type tile. */
    const val TILE_ALPHA: Float = 0.13f
  }
}

/** Light theme colors with [accent]. */
fun lightKetchColors(accent: KetchAccent = KetchAccent.Signal): KetchColors =
  ketchColors(accent, isDark = false)

/** Dark theme colors with [accent]. */
fun darkKetchColors(accent: KetchAccent = KetchAccent.Signal): KetchColors =
  ketchColors(accent, isDark = true)

/** The colors of [accent]; each neutral is written as `pick(light, dark)`. */
private fun ketchColors(accent: KetchAccent, isDark: Boolean): KetchColors {
  fun <T> pick(light: T, dark: T): T = if (isDark) dark else light
  val tone = KetchPalette.accentTone(accent, isDark)
  val inverseTone = KetchPalette.accentTone(accent, !isDark)
  val surface = pick(Color(0xFFFFFFFF), Color(0xFF16181D))
  val surfaceSunken = pick(Color(0xFFF4F6FA), Color(0xFF101217))
  val textTertiary = pick(Color(0xFF667080), Color(0xFF858D9C))
  fun status(color: Color): KetchStatusColor {
    return KetchStatusColor(color, color.copy(alpha = 0.10f).compositeOver(surface))
  }
  return KetchColors(
    canvas = pick(Color(0xFFEEF1F8), Color(0xFF0C0E13)),
    wash = pick(
      KetchWash(
        start = Color(0xFFE9EEFC),
        mid = Color(0xFFF1F0FA),
        end = Color(0xFFFBF0EA),
        ember = Color(0xFFFFD9C2).copy(alpha = 0.35f),
      ),
      KetchWash(
        start = Color(0xFF151A33),
        mid = Color(0xFF0E1016),
        end = Color(0xFF1C1310),
        ember = KetchPalette.EmberEnd.copy(alpha = 0.10f),
      ),
    ),
    surface = surface,
    surfaceRaised = pick(Color(0xFFFFFFFF), Color(0xFF1E2128)),
    surfaceSunken = surfaceSunken,
    surfaceHover = pick(Color(0xFFF1F3F8), Color(0xFF1F232A)),
    surfacePressed = pick(Color(0xFFE9ECF3), Color(0xFF272B33)),
    rowSelected = tone.fill.copy(alpha = pick(0.10f, 0.16f)).compositeOver(surface),
    rowSelectedFocused = tone.fill.copy(alpha = pick(0.15f, 0.24f)).compositeOver(surface),
    sidebarItemSelected = Color.White.copy(alpha = pick(0.72f, 0.08f)),
    sidebarItemHover = Color.White.copy(alpha = pick(0.45f, 0.05f)),
    hairline = pick(Color(0xFFE2E6EE), Color(0xFF2A2E37)),
    borderStrong = pick(Color(0xFFC9D0DC), Color(0xFF3A404C)),
    divider = pick(Color(0xFFEDF0F4), Color(0xFF22252C)),
    scrim = KetchPalette.ScrimInk.copy(alpha = pick(0.32f, 0.56f)),
    inverseSurface = pick(KetchPalette.Ink, Color(0xFFEEF0F4)),
    inverseOnSurface = pick(Color(0xFFF4F6FA), KetchPalette.Ink),
    inverseAccent = inverseTone.text,
    textPrimary = pick(KetchPalette.Ink, Color(0xFFEEF0F4)),
    textSecondary = pick(Color(0xFF4B5466), Color(0xFFAEB4C0)),
    textTertiary = textTertiary,
    textDisabled = pick(Color(0xFFA3AAB7), Color(0xFF565D6A)),
    accent = tone.fill,
    accentHover = pick(Color.Black, Color.White).copy(alpha = 0.08f).compositeOver(tone.fill),
    accentSoft = tone.soft,
    accentText = tone.text,
    onAccent = onFill(tone.fill),
    dangerFill = pick(Color(0xFFC8344A), Color(0xFFD2353F)),
    brandEmber = listOf(KetchPalette.EmberStart, KetchPalette.EmberEnd),
    status = KetchStatusColors(
      downloading = status(tone.fill),
      queued = status(pick(Color(0xFF646D7E), Color(0xFF8C94A3))),
      scheduled = status(pick(Color(0xFF6E4FE0), Color(0xFFA891FF))),
      paused = status(pick(Color(0xFF965700), Color(0xFFE8A93F))),
      completed = status(pick(Color(0xFF1B7540), Color(0xFF45C27A))),
      failed = status(pick(Color(0xFFBE2F44), Color(0xFFFF6B6B))),
      canceled = status(textTertiary),
      seeding = status(pick(Color(0xFF00747F), Color(0xFF4FD1DB))),
    ),
    lanes = laneColors(tone.fill, surfaceSunken),
    deviceHues = KetchPalette.DeviceHues,
    isDark = isDark,
  )
}

private fun laneColors(color: Color, track: Color): List<Color> =
  KetchPalette.LaneAlphas.map { color.copy(alpha = it).compositeOver(track) }

/** White when it reads at 4.5:1 on [fill], ink otherwise. */
private fun onFill(fill: Color): Color =
  if (contrastRatio(Color.White, fill) >= 4.5f) Color.White else KetchPalette.Ink

/** WCAG contrast ratio of two opaque colors, from 1 to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
  val x = a.luminance()
  val y = b.luminance()
  return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}
