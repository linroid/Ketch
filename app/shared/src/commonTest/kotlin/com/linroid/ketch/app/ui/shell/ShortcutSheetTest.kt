package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyPress
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutSheetTest {

  @Test
  fun shortcutGroups_mac_showsTheNineDeviceChordsAsOneLine() {
    val general = shortcutGroups(KeyboardPlatform.Mac) { true }.first { it.title == "General" }

    assertEquals(
      ShortcutLine("Switch to device 1–9", listOf("⌥⌘1–9")),
      general.lines.single { it.label.startsWith("Switch to device") },
    )
    assertEquals(listOf("⇧⌘P"), general.lines.single { it.label == "Pause all" }.keys)
  }

  @Test
  fun shortcutGroups_web_leavesOutTheWindowCommands() {
    val groups = shortcutGroups(KeyboardPlatform.WebPc) { true }
    val labels = groups.flatMap { it.lines }.map { it.label }

    assertFalse("Close window" in labels)
    assertFalse("Quit Ketch" in labels)
    assertTrue("New download…" in labels)
  }

  @Test
  fun shortcutGroups_commandTheWindowDoesNotRun_isLeftOut() {
    val groups = shortcutGroups(KeyboardPlatform.Pc) { it != KetchCommands.SwitchDevice }
    val labels = groups.flatMap { it.lines }.map { it.label }

    assertFalse(KetchCommands.SwitchDevice.label in labels)
    assertEquals(listOf("General", "Downloads list", "Add sheet"), groups.map { it.title })
  }

  @Test
  fun shellShortcuts_hostOwnsAChord_leavesItToTheHost() {
    val matcher = shellShortcuts(setOf(KetchCommands.Add), KeyboardPlatform.Mac)

    assertNull(matcher.match(KeyPress(Key.N, meta = true), ShortcutContext()))
    assertEquals(
      KetchCommands.Discover,
      matcher.match(KeyPress(Key.E, meta = true), ShortcutContext()),
    )
  }
}
