package com.linroid.ketch.app.input

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.state.StatusFilter
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ShortcutMatcherTest {
  private val mac = ShortcutMatcher(KeyboardPlatform.Mac)
  private val pc = ShortcutMatcher(KeyboardPlatform.Pc)
  private val webPc = ShortcutMatcher(KeyboardPlatform.WebPc)

  private val idle = ShortcutContext()
  private val listFocused = ShortcutContext(listFocused = true)
  private val typing = ShortcutContext(textFieldFocused = true)
  private val intake = ShortcutContext(overlay = CommandScope.Intake, textFieldFocused = true)

  @Test
  fun match_macPrimaryChord_needsMeta() {
    assertSame(KetchCommands.PauseAll, mac.match(KeyPress(Key.P, meta = true, shift = true), idle))
    assertNull(mac.match(KeyPress(Key.P, ctrl = true, shift = true), idle))
  }

  @Test
  fun match_pcPrimaryChord_needsCtrl() {
    assertSame(KetchCommands.PauseAll, pc.match(KeyPress(Key.P, ctrl = true, shift = true), idle))
    assertNull(pc.match(KeyPress(Key.P, meta = true, shift = true), idle))
  }

  @Test
  fun match_extraModifier_returnsNull() {
    val press = KeyPress(Key.P, meta = true, shift = true, alt = true)
    assertNull(mac.match(press, idle))
  }

  @Test
  fun match_altGrDigitOnPc_returnsNull() {
    assertSame(KetchCommands.device(1), pc.match(KeyPress(Key.One, alt = true), idle))
    assertNull(pc.match(KeyPress(Key.One, ctrl = true, alt = true), idle))
  }

  @Test
  fun match_listChordWithoutListFocus_returnsNull() {
    assertNull(mac.match(KeyPress(Key.Spacebar), idle))
    assertSame(KetchCommands.TogglePause, mac.match(KeyPress(Key.Spacebar), listFocused))
  }

  @Test
  fun match_listChordWhileTypingComposingOrInMenu_returnsNull() {
    val press = KeyPress(Key.Backspace)
    assertSame(KetchCommands.Remove, mac.match(press, listFocused))
    assertNull(mac.match(press, listFocused.copy(textFieldFocused = true)))
    assertNull(mac.match(press, listFocused.copy(composing = true)))
    assertNull(mac.match(press, listFocused.copy(menuOpen = true)))
  }

  @Test
  fun match_listFocused_stillRunsGlobalChords() {
    val press = KeyPress(Key.K, meta = true)
    assertSame(KetchCommands.Palette, mac.match(press, listFocused))
  }

  @Test
  fun match_pasteOrUndoWhileTyping_leavesKeyToTextField() {
    val paste = KeyPress(Key.V, meta = true)
    val undo = KeyPress(Key.Z, meta = true)
    assertSame(KetchCommands.PasteLinks, mac.match(paste, idle))
    assertSame(KetchCommands.Undo, mac.match(undo, listFocused))
    assertNull(mac.match(paste, typing))
    assertNull(mac.match(undo, typing))
  }

  @Test
  fun match_globalChordWhileTyping_runs() {
    assertSame(KetchCommands.Palette, mac.match(KeyPress(Key.K, meta = true), typing))
    assertSame(KetchCommands.Add, pc.match(KeyPress(Key.N, ctrl = true), typing))
  }

  @Test
  fun match_webSingleKeyWhileTyping_returnsNull() {
    assertSame(KetchCommands.Add, webPc.match(KeyPress(Key.N), idle))
    assertSame(KetchCommands.Shortcuts, webPc.match(KeyPress(Key.Slash, shift = true), idle))
    assertNull(webPc.match(KeyPress(Key.N), typing))
    assertSame(KetchCommands.Palette, webPc.match(KeyPress(Key.K, ctrl = true), typing))
  }

  @Test
  fun match_browserReservedChordOnWeb_returnsNull() {
    assertNull(webPc.match(KeyPress(Key.N, ctrl = true), idle))
    assertNull(webPc.match(KeyPress(Key.One, ctrl = true), idle))
    val tab = webPc.match(KeyPress(Key.One, alt = true), idle)
    assertSame(KetchCommands.tab(StatusFilter.All), tab)
  }

  @Test
  fun match_intakeOpen_prefersIntakeChords() {
    assertSame(KetchCommands.IntakeAdd, mac.match(KeyPress(Key.Enter), intake))
    assertSame(KetchCommands.IntakeOpenTorrent, mac.match(KeyPress(Key.O, meta = true), intake))
    val target = mac.match(KeyPress(Key.Two, meta = true, alt = true), intake)
    assertSame(KetchCommands.intakeTarget(2), target)
  }

  @Test
  fun match_intakeOpen_fallsBackToGlobalChords() {
    val press = KeyPress(Key.P, meta = true, shift = true)
    assertSame(KetchCommands.PauseAll, mac.match(press, intake))
  }

  @Test
  fun match_overlayWhileComposing_returnsNull() {
    assertNull(mac.match(KeyPress(Key.Enter), intake.copy(composing = true)))
  }

  @Test
  fun match_overlayOpen_ignoresListChords() {
    val palette = ShortcutContext(overlay = CommandScope.Palette, listFocused = true)
    assertNull(mac.match(KeyPress(Key.Spacebar), palette))
    assertSame(KetchCommands.PaletteUp, mac.match(KeyPress(Key.DirectionUp), palette))
  }

  @Test
  fun match_chordBoundTwiceInScope_runsFirstCommand() {
    val first = KetchCommands.Add
    val chord = KeyChord(Key.N, primary = true)
    val duplicate = KetchCommand(
      id = "duplicate",
      label = "Duplicate",
      icon = null,
      scope = CommandScope.Global,
      mac = chord,
      pc = chord,
      web = null,
      inMenus = false,
      onWeb = true,
      yieldsToTextField = false,
    )
    val matcher = ShortcutMatcher(KeyboardPlatform.Mac, listOf(first, duplicate))
    assertSame(first, matcher.match(KeyPress(Key.N, meta = true), idle))
  }

  @Test
  fun init_listAsOverlay_throws() {
    assertFailsWith<IllegalArgumentException> { ShortcutContext(overlay = CommandScope.List) }
  }
}
