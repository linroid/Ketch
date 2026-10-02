package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.StatusFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostShortcutsTest {

  @Test
  fun hostShortcuts_mac_leavesTheMenuBarChordsToTheMenuBar() {
    val owned = hostShortcuts(DesktopOs.MAC, slowLane = true)

    for (command in listOf(
      KetchCommands.Add,
      KetchCommands.AddClipboardLink,
      KetchCommands.PauseAll,
      KetchCommands.ResumeAll,
      KetchCommands.RetryFailed,
      KetchCommands.SlowLane,
      KetchCommands.Search,
      KetchCommands.ToggleInspector,
      KetchCommands.ToggleSidebar,
      KetchCommands.Palette,
      KetchCommands.Discover,
      KetchCommands.Devices,
      KetchCommands.Activity,
      KetchCommands.tab(StatusFilter.Failed),
      KetchCommands.CloseWindow,
      KetchCommands.Minimize,
      KetchCommands.Settings,
    )) {
      assertTrue(command in owned, "${command.id} belongs to the menu bar")
    }
  }

  @Test
  fun hostShortcuts_mac_keepsTheChordsTheMenuBarDoesNotList() {
    val owned = hostShortcuts(DesktopOs.MAC, slowLane = false)

    assertFalse(KetchCommands.SlowLane in owned)
    for (command in listOf(
      KetchCommands.PasteLinks,
      KetchCommands.OpenTorrent,
      KetchCommands.SwitchDevice,
      KetchCommands.Undo,
      KetchCommands.Shortcuts,
      KetchCommands.device(1),
    )) {
      assertFalse(command in owned, "${command.id} is the shell's")
    }
  }

  @Test
  fun hostShortcuts_windowsAndLinux_ownOnlyTheWindowsChords() {
    val expected = setOf(KetchCommands.Settings, KetchCommands.CloseWindow, KetchCommands.Quit)

    assertEquals(expected, hostShortcuts(DesktopOs.WINDOWS, slowLane = true))
    assertEquals(expected, hostShortcuts(DesktopOs.LINUX, slowLane = true))
  }
}
