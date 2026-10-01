package com.linroid.ketch.app.input

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyChordTest {

  @Test
  fun resolve_apple_mapsPrimaryToMeta() {
    val chord = KeyChord(Key.P, primary = true, shift = true)
    assertEquals(KeyPress(Key.P, meta = true, shift = true), chord.resolve(KeyboardPlatform.Mac))
    assertEquals(KeyPress(Key.P, meta = true, shift = true), chord.resolve(KeyboardPlatform.WebMac))
  }

  @Test
  fun resolve_pc_mapsPrimaryToCtrl() {
    val chord = KeyChord(Key.P, primary = true, shift = true)
    assertEquals(KeyPress(Key.P, ctrl = true, shift = true), chord.resolve(KeyboardPlatform.Pc))
    assertEquals(KeyPress(Key.P, ctrl = true, shift = true), chord.resolve(KeyboardPlatform.WebPc))
  }

  @Test
  fun resolve_appleControlWithPrimary_holdsBoth() {
    val chord = KeyChord(Key.S, primary = true, ctrl = true)
    assertEquals(KeyPress(Key.S, meta = true, ctrl = true), chord.resolve(KeyboardPlatform.Mac))
  }

  @Test
  fun label_allModifiers_followPlatformOrder() {
    val chord = KeyChord(Key.K, primary = true, shift = true, alt = true, ctrl = true)
    assertEquals("⌃⌥⇧⌘K", chord.label(KeyboardPlatform.Mac))
    assertEquals("Ctrl+Alt+Shift+K", chord.label(KeyboardPlatform.Pc))
  }

  @Test
  fun label_letterWithoutModifiers_isLowercase() {
    assertEquals("n", KeyChord(Key.N).label(KeyboardPlatform.WebPc))
    assertEquals("Shift+N", KeyChord(Key.N, shift = true).label(KeyboardPlatform.WebPc))
    assertEquals("⇧N", KeyChord(Key.N, shift = true).label(KeyboardPlatform.WebMac))
  }

  @Test
  fun label_shiftSlash_printsQuestionMark() {
    KeyboardPlatform.entries.forEach { platform ->
      assertEquals("?", KeyChord(Key.Slash, shift = true).label(platform))
    }
  }

  @Test
  fun label_namedKeys_followPlatform() {
    val keys = listOf(Key.Enter, Key.Backspace, Key.Delete, Key.Escape, Key.Spacebar, Key.PageUp)
    assertEquals(
      listOf("↩", "⌫", "⌦", "Esc", "Space", "PgUp"),
      keys.map { KeyChord(it).label(KeyboardPlatform.Mac) },
    )
    assertEquals(
      listOf("Enter", "Backspace", "Delete", "Esc", "Space", "PgUp"),
      keys.map { KeyChord(it).label(KeyboardPlatform.Pc) },
    )
  }

  @Test
  fun init_keyWithoutPrintableName_throws() {
    assertFailsWith<IllegalArgumentException> { KeyChord(Key.F1) }
  }

  @Test
  fun typesText_printableKeyWithoutCommandModifier_isTrue() {
    assertTrue(KeyChord(Key.N).typesText(KeyboardPlatform.WebPc))
    assertTrue(KeyChord(Key.Slash, shift = true).typesText(KeyboardPlatform.WebPc))
    assertFalse(KeyChord(Key.N, primary = true).typesText(KeyboardPlatform.WebPc))
    assertFalse(KeyChord(Key.Escape).typesText(KeyboardPlatform.WebPc))
  }

  @Test
  fun typesText_altDigit_isTrueOnlyOnApple() {
    val chord = KeyChord(Key.One, alt = true)
    assertTrue(chord.typesText(KeyboardPlatform.WebMac))
    assertFalse(chord.typesText(KeyboardPlatform.WebPc))
  }
}
