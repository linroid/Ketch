package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyPress
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutSheetTest {

  @Test
  fun shortcutGroups_mac_showsTheNineDeviceChordsAsOneLine() = runTest {
    val general = shortcutGroups(KeyboardPlatform.Mac) { true }.first {
      it.title.load() == "General"
    }

    val devices = general.lines.single { it.label.load().startsWith("Switch to device") }
    assertEquals("Switch to device 1–9", devices.label.load())
    assertEquals(listOf("⌥⌘1–9"), devices.keys)
    assertEquals(listOf("⇧⌘P"), general.lines.single { it.label.load() == "Pause all" }.keys)
  }

  @Test
  fun shortcutGroups_web_leavesOutTheWindowCommands() = runTest {
    val groups = shortcutGroups(KeyboardPlatform.WebPc) { true }
    val labels = groups.flatMap { it.lines }.map { it.label }.load()

    assertFalse("Close window" in labels)
    assertFalse("Quit Ketch" in labels)
    assertTrue("New download…" in labels)
  }

  @Test
  fun shortcutGroups_commandTheWindowDoesNotRun_isLeftOut() = runTest {
    val groups = shortcutGroups(KeyboardPlatform.Pc) { it != KetchCommands.SwitchDevice }
    val labels = groups.flatMap { it.lines }.map { it.label }

    assertFalse(KetchCommands.SwitchDevice.label in labels)
    assertEquals(listOf("General", "Downloads list", "Add sheet"), groups.map { it.title }.load())
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
