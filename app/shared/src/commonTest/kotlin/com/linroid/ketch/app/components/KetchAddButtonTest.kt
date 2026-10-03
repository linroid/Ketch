package com.linroid.ketch.app.components

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.component_add_clip
import ketch.app.shared.generated.resources.component_add_link
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetchAddButtonTest {
  private val clip = AddButtonMode.Clip(name = "ubuntu.iso", description = "", id = "hash-1")

  @Test
  fun addGlyph_plain_splitsIntoLanesOnlyWhileHovered() {
    assertEquals(AddGlyph.Plus, addGlyph(AddButtonMode.Plain, hovered = false))
    assertEquals(AddGlyph.Lanes, addGlyph(AddButtonMode.Plain, hovered = true))
  }

  @Test
  fun addGlyph_clipAndDrop_keepTheirGlyphWhetherHoveredOrNot() {
    for (hovered in listOf(false, true)) {
      assertEquals(AddGlyph.Arrow, addGlyph(clip, hovered))
      assertEquals(AddGlyph.Lanes, addGlyph(AddButtonMode.Drop(over = false), hovered))
    }
  }

  @Test
  fun addGlows_drop_glowsOnlyWhileTheDragIsOverIt() {
    assertFalse(addGlows(AddButtonMode.Drop(over = false), hovered = true))
    assertTrue(addGlows(AddButtonMode.Drop(over = true), hovered = false))
    assertTrue(addGlows(clip, hovered = true))
    assertFalse(addGlows(AddButtonMode.Plain, hovered = false))
  }

  @Test
  fun addLanesFlow_reduceMotion_keepsTheLanesStill() {
    assertTrue(addLanesFlow(AddButtonMode.Drop(over = false), reduced = false))
    assertFalse(addLanesFlow(AddButtonMode.Drop(over = false), reduced = true))
    assertFalse(addLanesFlow(AddButtonMode.Plain, reduced = false))
  }

  @Test
  fun addShimmers_newClip_playsOnce() {
    assertTrue(addShimmers(clip, shimmered = null, reduced = false))
    assertFalse(addShimmers(clip, shimmered = "hash-1", reduced = false))
    assertTrue(addShimmers(clip, shimmered = "hash-0", reduced = false))
  }

  @Test
  fun addShimmers_reduceMotionOrNoClip_neverPlays() {
    assertFalse(addShimmers(clip, shimmered = null, reduced = true))
    assertFalse(addShimmers(AddButtonMode.Plain, shimmered = null, reduced = false))
  }

  @Test
  fun addLabel_eachMode_namesWhatAClickDoes() = runTest {
    assertEquals("Add", addLabel(AddButtonMode.Plain).load())
    assertEquals("Add ubuntu.iso", addLabel(clip).load())
    assertEquals("Drop to download", addLabel(AddButtonMode.Drop(over = true)).load())
  }

  @Test
  fun shortFileName_fits_isUnchanged() {
    assertEquals("ubuntu.iso", shortFileName("ubuntu.iso", 24))
    val exact = "a".repeat(20) + ".iso"
    assertEquals(exact, shortFileName(exact, 24))
  }

  @Test
  fun shortFileName_long_keepsTheStartAndTheLastWords() {
    assertEquals(
      "Fedora-Workst…41-1.4.iso",
      shortFileName("Fedora-Workstation-Live-x86_64-41-1.4.iso", 24),
    )
  }

  @Test
  fun shortFileName_noWordToStartAt_cutsMidWord() {
    val name = "abcdefghijklmnopqrstuvwxyz0123456789.iso"

    val short = shortFileName(name, 24)

    assertEquals(24, short.length)
    assertTrue(short.startsWith("abcdefghijkl…"))
    assertTrue(short.endsWith(".iso"))
  }

  @Test
  fun shortFileName_wordCutAFewLettersIn_leavesItOut() {
    assertEquals("ubuntu-24.04…amd64.iso", shortFileName("ubuntu-24.04-desktop-amd64.iso", 24))
  }

  @Test
  fun shortFileName_noExtension_keepsWholeWords() {
    assertEquals(
      "release…linux",
      shortFileName("release-candidate-build-artifact-linux", max = 16),
    )
  }

  @Test
  fun fitClipLabel_room_takesTheLongestNameThatFits() = runTest {
    val name = "ubuntu-24.04.1-desktop-amd64.iso"

    assertEquals("Add ubuntu-24.04.1…amd64.iso", fitClipLabel(name) { true })
    assertEquals("Add ubuntu…amd64.iso", fitClipLabel(name) { it.length <= 20 })
  }

  @Test
  fun fitClipLabel_noRoomForAShortName_saysLink() = runTest {
    assertEquals("Add link", fitClipLabel("ubuntu-24.04.1-desktop-amd64.iso") { it.length < 14 })
  }

  // The label as the button fits it, with its English text.
  private suspend fun fitClipLabel(name: String, fits: (String) -> Boolean): String = fitClipLabel(
    name = name,
    template = Res.string.component_add_clip.text(CLIP_NAME_SLOT).load(),
    fallback = Res.string.component_add_link.text().load(),
    fits = fits,
  )
}
