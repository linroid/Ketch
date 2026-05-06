package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class KetchColors(
  // Surfaces
  val background: Color,
  val surface: Color,
  val surfaceVariant: Color,
  val surfaceHover: Color,

  // Borders
  val outline: Color,
  val outlineVariant: Color,

  // Content
  val onBackground: Color,
  val onSurfaceVariant: Color,
  val onSurfaceDim: Color,

  // Accent
  val primary: Color,
  val primaryContainer: Color,
  val onPrimaryContainer: Color,

  // Status
  val success: Color,
  val warning: Color,
  val error: Color,

  // Eight-color palette for segment visualization
  val segments: List<Color>,

  val isDark: Boolean,
) {
  // Descriptive aliases used throughout the mocks
  val bg: Color get() = background
  val bgElev: Color get() = surface
  val panel: Color get() = surfaceVariant
  val bgHover: Color get() = surfaceHover
  val line: Color get() = outline
  val lineSoft: Color get() = outlineVariant
  val text: Color get() = onBackground
  val textSec: Color get() = onSurfaceVariant
  val textDim: Color get() = onSurfaceDim
  val accent: Color get() = primary
  val accentSoft: Color get() = primaryContainer
  val accentText: Color get() = onPrimaryContainer
}

enum class KetchAccent(val displayName: String) {
  Signal("Signal"),
  Harbor("Harbor"),
  Fathom("Fathom"),
  Beacon("Beacon"),
}

internal object KetchPalette {

  object Dark {
    val bg = Color(0xFF101214)
    val bgElev = Color(0xFF16191B)
    val bgHover = Color(0xFF1F2225)
    val panel = Color(0xFF1B1D20)
    val line = Color(0xFF2B2E32)
    val lineSoft = Color(0xFF222427)
    val text = Color(0xFFF0F2F4)
    val textSec = Color(0xFFA1A5A9)
    val textDim = Color(0xFF6E7276)
    val success = Color(0xFF54B66E)
    val warning = Color(0xFFE6AC3D)
    val danger = Color(0xFFF05F5A)
  }

  object Light {
    val bg = Color(0xFFF9FAFB)
    val bgElev = Color(0xFFFFFFFF)
    val bgHover = Color(0xFFEDEFF0)
    val panel = Color(0xFFF5F7F9)
    val line = Color(0xFFDFE1E4)
    val lineSoft = Color(0xFFEAEBED)
    val text = Color(0xFF13161A)
    val textSec = Color(0xFF51565B)
    val textDim = Color(0xFF83878B)
    val success = Color(0xFF2A904B)
    val warning = Color(0xFFD58300)
    val danger = Color(0xFFDE3B3D)
  }

  data class AccentTriple(val primary: Color, val container: Color, val onContainer: Color)

  val SignalLight = AccentTriple(Color(0xFF0076D8), Color(0xFFD8EEFF), Color(0xFF005DBD))
  val SignalDark = AccentTriple(Color(0xFF319CFC), Color(0xFF01345E), Color(0xFF6DBDFF))
  val HarborLight = AccentTriple(Color(0xFF00909E), Color(0xFFCDF4F6), Color(0xFF007785))
  val HarborDark = AccentTriple(Color(0xFF00B5C1), Color(0xFF003F45), Color(0xFF00D1DA))
  val FathomLight = AccentTriple(Color(0xFF008F32), Color(0xFFD9F3DD), Color(0xFF007717))
  val FathomDark = AccentTriple(Color(0xFF2EB45C), Color(0xFF003F17), Color(0xFF6FD087))
  val BeaconLight = AccentTriple(Color(0xFFBF4C00), Color(0xFFFFE5D2), Color(0xFFA43200))
  val BeaconDark = AccentTriple(Color(0xFFE57600), Color(0xFF542300), Color(0xFFFB9D59))

  val SignalSegmentsLight = listOf(
    Color(0xFF007CDF), Color(0xFF4697E4), Color(0xFF005EB3), Color(0xFF67AAED),
    Color(0xFF004E95), Color(0xFF85BCF5), Color(0xFF00437F), Color(0xFF9DC9F7),
  )
  val SignalSegmentsDark = listOf(
    Color(0xFF42A3FD), Color(0xFF4393E1), Color(0xFF3C7EBE), Color(0xFF6DB0F4),
    Color(0xFF116BB5), Color(0xFF8CC3FC), Color(0xFF125A98), Color(0xFF90BCE9),
  )
  val HarborSegmentsLight = listOf(
    Color(0xFF0096A4), Color(0xFF00AAB4), Color(0xFF007581), Color(0xFF14BBC2),
    Color(0xFF00616B), Color(0xFF5DCBD1), Color(0xFF00535B), Color(0xFF83D4D8),
  )
  val HarborSegmentsDark = listOf(
    Color(0xFF00BAC5), Color(0xFF00A7B1), Color(0xFF008E96), Color(0xFF24C1C9),
    Color(0xFF007F88), Color(0xFF64D1D7), Color(0xFF006A72), Color(0xFF76C7CC),
  )
  val FathomSegmentsLight = listOf(
    Color(0xFF009639), Color(0xFF47AA62), Color(0xFF007424), Color(0xFF69BA7C),
    Color(0xFF00601C), Color(0xFF88CA95), Color(0xFF00531B), Color(0xFF9FD3A9),
  )
  val FathomSegmentsDark = listOf(
    Color(0xFF43B966), Color(0xFF43A65F), Color(0xFF3D8E53), Color(0xFF6FC082),
    Color(0xFF0A7E3A), Color(0xFF8ED09C), Color(0xFF0F6A31), Color(0xFF93C69D),
  )
  val BeaconSegmentsLight = listOf(
    Color(0xFFC65300), Color(0xFFD27830), Color(0xFF9D3A00), Color(0xFFDE8F57),
    Color(0xFF832F00), Color(0xFFE9A679), Color(0xFF702A00), Color(0xFFEDB793),
  )
  val BeaconSegmentsDark = listOf(
    Color(0xFFE87F25), Color(0xFFCF752D), Color(0xFFB0652A), Color(0xFFE5955D),
    Color(0xFFA34D00), Color(0xFFF0AD7F), Color(0xFF894100), Color(0xFFE0AA86),
  )
}

fun lightKetchColors(accent: KetchAccent = KetchAccent.Signal): KetchColors {
  val a = when (accent) {
    KetchAccent.Signal -> KetchPalette.SignalLight
    KetchAccent.Harbor -> KetchPalette.HarborLight
    KetchAccent.Fathom -> KetchPalette.FathomLight
    KetchAccent.Beacon -> KetchPalette.BeaconLight
  }
  val segs = when (accent) {
    KetchAccent.Signal -> KetchPalette.SignalSegmentsLight
    KetchAccent.Harbor -> KetchPalette.HarborSegmentsLight
    KetchAccent.Fathom -> KetchPalette.FathomSegmentsLight
    KetchAccent.Beacon -> KetchPalette.BeaconSegmentsLight
  }
  return KetchColors(
    background = KetchPalette.Light.bg,
    surface = KetchPalette.Light.bgElev,
    surfaceVariant = KetchPalette.Light.panel,
    surfaceHover = KetchPalette.Light.bgHover,
    outline = KetchPalette.Light.line,
    outlineVariant = KetchPalette.Light.lineSoft,
    onBackground = KetchPalette.Light.text,
    onSurfaceVariant = KetchPalette.Light.textSec,
    onSurfaceDim = KetchPalette.Light.textDim,
    primary = a.primary,
    primaryContainer = a.container,
    onPrimaryContainer = a.onContainer,
    success = KetchPalette.Light.success,
    warning = KetchPalette.Light.warning,
    error = KetchPalette.Light.danger,
    segments = segs,
    isDark = false,
  )
}

fun darkKetchColors(accent: KetchAccent = KetchAccent.Signal): KetchColors {
  val a = when (accent) {
    KetchAccent.Signal -> KetchPalette.SignalDark
    KetchAccent.Harbor -> KetchPalette.HarborDark
    KetchAccent.Fathom -> KetchPalette.FathomDark
    KetchAccent.Beacon -> KetchPalette.BeaconDark
  }
  val segs = when (accent) {
    KetchAccent.Signal -> KetchPalette.SignalSegmentsDark
    KetchAccent.Harbor -> KetchPalette.HarborSegmentsDark
    KetchAccent.Fathom -> KetchPalette.FathomSegmentsDark
    KetchAccent.Beacon -> KetchPalette.BeaconSegmentsDark
  }
  return KetchColors(
    background = KetchPalette.Dark.bg,
    surface = KetchPalette.Dark.bgElev,
    surfaceVariant = KetchPalette.Dark.panel,
    surfaceHover = KetchPalette.Dark.bgHover,
    outline = KetchPalette.Dark.line,
    outlineVariant = KetchPalette.Dark.lineSoft,
    onBackground = KetchPalette.Dark.text,
    onSurfaceVariant = KetchPalette.Dark.textSec,
    onSurfaceDim = KetchPalette.Dark.textDim,
    primary = a.primary,
    primaryContainer = a.container,
    onPrimaryContainer = a.onContainer,
    success = KetchPalette.Dark.success,
    warning = KetchPalette.Dark.warning,
    error = KetchPalette.Dark.danger,
    segments = segs,
    isDark = true,
  )
}
