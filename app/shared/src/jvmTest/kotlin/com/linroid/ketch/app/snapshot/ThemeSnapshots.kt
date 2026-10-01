package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Every text style at both densities, and a check that the bundled fonts are the ones that
 * render; see [SnapshotHarness] for how to run it.
 */
class ThemeSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun typography_bothDensitiesAndThemes_rendersEveryStyle() {
    for (density in KetchDensity.entries) {
      for (theme in SnapshotTheme.entries) {
        val size = SnapshotSize(720.dp, 760.dp, density)
        snapshot("type-${density.name.lowercase()}", size, theme) { TypeSpecimen() }
      }
    }
  }

  @Test
  fun bundledFonts_measuredInTheTheme_matchTheFontFiles() {
    // The platform's UI font looks much like Inter, so compare widths rather than looks.
    val widths = mutableMapOf<String, Int>()
    val size = SnapshotSize(720.dp, 160.dp, KetchDensity.Compact)
    snapshot("type-check", size, SnapshotTheme.Light) {
      val measurer = rememberTextMeasurer()
      val type = KetchTheme.typography
      val measured = mapOf(
        "theme sans" to type.body,
        "file sans" to type.body.copy(fontFamily = fileFamily("inter_regular")),
        "platform sans" to type.body.copy(fontFamily = FontFamily.Default),
        "theme mono" to type.mono,
        "file mono" to type.mono.copy(fontFamily = fileFamily("jetbrainsmono_regular")),
      ).mapValues { (_, style) -> measurer.measure(SAMPLE, style).size.width }
      SideEffect { widths += measured }
      Column(Modifier.padding(KetchTheme.spacing.s4)) {
        for ((name, width) in measured) {
          Text("$name: $width px", style = type.mono, color = KetchTheme.colors.textPrimary)
        }
      }
    }
    assertEquals(widths["file sans"], widths["theme sans"], "Inter is not the sans")
    assertNotEquals(widths["platform sans"], widths["theme sans"], "Inter is the platform font")
    assertEquals(widths["file mono"], widths["theme mono"], "JetBrains Mono is not the mono")
  }

  private fun fileFamily(name: String): FontFamily {
    val path = "composeResources/ketch.app.shared.generated.resources/font/$name.ttf"
    val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "No $path" }
      .use { it.readBytes() }
    return FontFamily(Font(name, bytes, FontWeight.Normal))
  }

  private companion object {
    const val SAMPLE = "ubuntu-24.04-desktop-amd64.iso · 18.4 MB/s · Ag ffi 0O 1Il"
  }
}

@Composable
private fun TypeSpecimen() {
  val type = KetchTheme.typography
  val styles = listOf(
    "largeTitle" to type.largeTitle,
    "pageTitle" to type.pageTitle,
    "titleL" to type.titleL,
    "titleM" to type.titleM,
    "bodyStrong" to type.bodyStrong,
    "body" to type.body,
    "bodyS" to type.bodyS,
    "cellStrong" to type.cellStrong,
    "cell" to type.cell,
    "label" to type.label,
    "labelS" to type.labelS,
    "caption" to type.caption,
    "eyebrow" to type.eyebrow,
    "numeralXL" to type.numeralXL,
    "numeral" to type.numeral,
    "mono" to type.mono,
  )
  Column(
    modifier = Modifier.padding(KetchTheme.spacing.s6),
    verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
  ) {
    for ((name, style) in styles) SpecimenLine(name, style)
  }
}

@Composable
private fun SpecimenLine(name: String, style: TextStyle) {
  val sample = if (name.startsWith("numeral") || name == "mono") {
    "$name 18.4 MB/s · 62% · 1,234,567 B · 0O 1lI"
  } else {
    "$name · ubuntu-24.04-desktop-amd64.iso · Ag ffi 0O 1lI"
  }
  Text(sample, style = style, color = KetchTheme.colors.textPrimary, maxLines = 1)
}
