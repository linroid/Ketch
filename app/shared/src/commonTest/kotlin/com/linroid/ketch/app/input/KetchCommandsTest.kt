package com.linroid.ketch.app.input

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.StatusFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KetchCommandsTest {

  /** Chords browsers keep for themselves, plus ⌘1 to ⌘9, which switch browser tabs. */
  private val browserReserved = listOf(
    KeyChord(Key.N, primary = true),
    KeyChord(Key.T, primary = true),
    KeyChord(Key.W, primary = true),
    KeyChord(Key.R, primary = true),
    KeyChord(Key.P, primary = true, shift = true),
    KeyChord(Key.R, primary = true, shift = true),
  ) + (1..9).map { KeyChord(digitKeys[it], primary = true) }

  private val webPlatforms = KeyboardPlatform.entries.filter { it.isWeb }

  @Test
  fun all_ids_areUnique() {
    val duplicates = KetchCommands.all.groupBy { it.id }.filterValues { it.size > 1 }.keys
    assertTrue(duplicates.isEmpty(), "Duplicate ids: $duplicates")
  }

  @Test
  fun all_eachScopeOnEachPlatform_bindsEveryKeyPressOnce() {
    for (platform in KeyboardPlatform.entries) {
      for (scope in CommandScope.entries) {
        val owners = KetchCommands.all.filter { it.scope == scope }
          .flatMap { command -> command.chords(platform).map { it.resolve(platform) to command } }
          .groupBy({ it.first }, { it.second.id })
        val duplicates = owners.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "$platform $scope binds twice: $duplicates")
      }
    }
  }

  @Test
  fun all_listChordsOnEachPlatform_neverHideGlobalChords() {
    for (platform in KeyboardPlatform.entries) {
      val hidden = presses(CommandScope.List, platform) intersect
        presses(CommandScope.Global, platform)
      assertTrue(hidden.isEmpty(), "$platform list chords hide global ones: $hidden")
    }
  }

  @Test
  fun all_everyCommand_hasLabel() = runTest {
    KetchCommands.all.forEach { command ->
      assertTrue(command.label.load().isNotBlank(), "${command.id} has no label")
    }
  }

  @Test
  fun label_everyChordOnEveryPlatform_isPrintable() {
    for (platform in KeyboardPlatform.entries) {
      for (command in KetchCommands.all) {
        command.chords(platform).forEach { chord ->
          val label = chord.label(platform)
          assertTrue(label.isNotBlank() && label.none { it.isWhitespace() }, "${command.id}")
        }
      }
    }
  }

  @Test
  fun shortcutLabel_globalCommands_followEachKeymap() {
    assertLabels(KetchCommands.PauseAll, "⇧⌘P", "Ctrl+Shift+P", "⇧P", "Shift+P")
    assertLabels(KetchCommands.RetryFailed, "⌥⌘R", "Ctrl+Alt+R", "⌥⌘R", "Ctrl+Alt+R")
    assertLabels(KetchCommands.ToggleSidebar, "⌃⌘S", "Ctrl+Shift+S", "⌃⌘S", "Ctrl+Shift+S")
    assertLabels(KetchCommands.device(2), "⌥⌘2", "Alt+2", "⌥⇧2", "Alt+Shift+2")
    assertLabels(KetchCommands.AllDevices, "⌥⌘0", "Alt+0", "⌥⇧0", "Alt+Shift+0")
    assertLabels(KetchCommands.Add, "⌘N", "Ctrl+N", "n", "n")
    assertLabels(KetchCommands.Search, "⌘F", "Ctrl+F", "/", "/")
    assertLabels(KetchCommands.Shortcuts, "⌘/", "Ctrl+/", "?", "?")
    assertLabels(KetchCommands.Settings, "⌘,", "Ctrl+,", "⌘,", "Ctrl+,")
    assertLabels(KetchCommands.Minimize, "⌘M", null, null, null)
  }

  @Test
  fun shortcutLabel_listCommands_followEachKeymap() {
    assertLabels(KetchCommands.Remove, "⌫", "Delete", "⌫", "Delete")
    assertLabels(KetchCommands.RemoveAndTrash, "⇧⌫", "Shift+Delete", "⇧⌫", "Shift+Delete")
    assertLabels(KetchCommands.Reveal, "⌘↩", "Ctrl+Enter", "⌘↩", "Ctrl+Enter")
    assertLabels(KetchCommands.CopyPath, "⌥⌘C", "Ctrl+Alt+C", "⌥⌘C", "Ctrl+Alt+C")
    assertLabels(KetchCommands.RaisePriority, "⌥⌘↑", "Ctrl+Alt+↑", "⌥⌘↑", "Ctrl+Alt+↑")
    assertLabels(KetchCommands.FewerConnections, "−", "-", "−", "-")
    assertLabels(KetchCommands.TogglePause, "Space", "Space", "Space", "Space")
    assertLabels(KetchCommands.Retry, "⌘R", "Ctrl+R", "r", "r")
  }

  @Test
  fun chords_palette_keepsPrimaryChordOnWeb() {
    val labels = KetchCommands.Palette.chords(KeyboardPlatform.WebPc)
      .map { it.label(KeyboardPlatform.WebPc) }
    assertEquals(listOf("Ctrl+K", "k"), labels)
  }

  @Test
  fun chords_web_avoidBrowserReservedChords() {
    for (platform in webPlatforms) {
      val reserved = browserReserved.map { it.resolve(platform) }.toSet()
      for (command in KetchCommands.all) {
        val taken = command.chords(platform).filter { it.resolve(platform) in reserved }
        assertTrue(taken.isEmpty(), "${command.id} uses $taken on $platform")
      }
    }
  }

  @Test
  fun chords_reservedDesktopChord_hasWebFallback() {
    for (platform in webPlatforms) {
      val desktop = if (platform.isApple) KeyboardPlatform.Mac else KeyboardPlatform.Pc
      val reserved = browserReserved.map { it.resolve(platform) }.toSet()
      for (command in KetchCommands.all.filter { it.onWeb }) {
        val lost = command.chords(desktop).any { it.resolve(platform) in reserved }
        if (lost) {
          assertTrue(command.chords(platform).isNotEmpty(), "${command.id} on $platform")
        }
      }
    }
  }

  @Test
  fun chords_commandNotOnWeb_hasNoWebChords() {
    for (platform in webPlatforms) {
      assertTrue(KetchCommands.CloseWindow.chords(platform).isEmpty())
      assertTrue(KetchCommands.Quit.chords(platform).isEmpty())
    }
    assertEquals("⌘W", KetchCommands.CloseWindow.shortcutLabel(KeyboardPlatform.Mac))
  }

  @Test
  fun chords_pc_neverHoldCtrlWithPrimary() {
    for (platform in KeyboardPlatform.entries.filterNot { it.isApple }) {
      for (command in KetchCommands.all) {
        command.chords(platform).forEach { chord ->
          assertTrue(!(chord.primary && chord.ctrl), "${command.id} on $platform")
        }
      }
    }
  }

  @Test
  fun tab_eachFilter_bindsItsPosition() {
    StatusFilter.entries.forEachIndexed { index, filter ->
      val command = KetchCommands.tab(filter)
      assertEquals(filter.label, command.label)
      assertEquals("⌘${index + 1}", command.shortcutLabel(KeyboardPlatform.Mac))
      assertEquals("Alt+${index + 1}", command.shortcutLabel(KeyboardPlatform.WebPc))
    }
  }

  @Test
  fun device_numberOutsideOneToNine_throws() {
    assertFailsWith<IllegalArgumentException> { KetchCommands.device(0) }
    assertFailsWith<IllegalArgumentException> { KetchCommands.device(10) }
    assertFailsWith<IllegalArgumentException> { KetchCommands.intakeTarget(0) }
  }

  private fun presses(scope: CommandScope, platform: KeyboardPlatform): Set<KeyPress> =
    KetchCommands.all.filter { it.scope == scope }
      .flatMap { command -> command.chords(platform).map { it.resolve(platform) } }
      .toSet()

  private fun assertLabels(
    command: KetchCommand,
    mac: String?,
    pc: String?,
    webMac: String?,
    webPc: String?,
  ) {
    val actual = listOf(
      KeyboardPlatform.Mac,
      KeyboardPlatform.Pc,
      KeyboardPlatform.WebMac,
      KeyboardPlatform.WebPc,
    ).map { command.shortcutLabel(it) }
    assertEquals(listOf(mac, pc, webMac, webPc), actual, command.id)
  }
}
