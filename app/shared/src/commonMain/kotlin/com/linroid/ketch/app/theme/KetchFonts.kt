package com.linroid.ketch.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inter_medium
import ketch.app.shared.generated.resources.inter_regular
import ketch.app.shared.generated.resources.inter_semibold
import ketch.app.shared.generated.resources.interdisplay_bold
import ketch.app.shared.generated.resources.interdisplay_semibold
import ketch.app.shared.generated.resources.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/** One bundled font file and the weight it provides. */
internal class KetchFont(val resource: FontResource, val weight: FontWeight)

private val InterFonts = listOf(
  KetchFont(Res.font.inter_regular, FontWeight.Normal),
  KetchFont(Res.font.inter_medium, FontWeight.Medium),
  KetchFont(Res.font.inter_semibold, FontWeight.SemiBold),
)
private val InterDisplayFonts = listOf(
  KetchFont(Res.font.interdisplay_semibold, FontWeight.SemiBold),
  KetchFont(Res.font.interdisplay_bold, FontWeight.Bold),
)
private val MonoFonts = listOf(KetchFont(Res.font.jetbrainsmono_regular, FontWeight.Normal))

/**
 * The text styles at [density], set in the bundled Inter, Inter Display and JetBrains Mono.
 *
 * Characters outside the bundled Latin, Greek and Cyrillic subsets, such as CJK, fall back to
 * the system fonts. The web has none; there Compose downloads Noto fonts for them as needed.
 */
@Composable
fun rememberKetchTypography(density: KetchDensity = KetchDensity.Compact): KetchTypography {
  val sans = fontFamily(InterFonts)
  val display = fontFamily(InterDisplayFonts)
  val mono = fontFamily(MonoFonts)
  return remember(sans, display, mono, density) { ketchTypography(sans, display, mono, density) }
}

/**
 * Whether the bundled fonts are ready, so text shows in them from its first frame.
 *
 * Only the web loads fonts asynchronously; there it starts loading them and returns `false`
 * until they are cached. Other platforms always return `true`. The web app shows its HTML
 * splash until this returns `true`.
 */
@Composable
fun rememberKetchFontsLoaded(): Boolean =
  rememberFontsPreloaded(InterFonts + InterDisplayFonts + MonoFonts)

@Composable
private fun fontFamily(fonts: List<KetchFont>): FontFamily {
  val loaded = fonts.map { Font(it.resource, it.weight) }
  return remember(loaded) { FontFamily(loaded) }
}

/** Starts loading [fonts] where that is asynchronous; `true` once all of them are ready. */
@Composable
internal expect fun rememberFontsPreloaded(fonts: List<KetchFont>): Boolean
