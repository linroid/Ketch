package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyPress
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SelectionState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.rowOf
import com.linroid.ketch.app.state.taskActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListKeyboardTest {
  private val downloading = ListFixtures.downloading(400)
  private val paused = DownloadState.Paused(DownloadProgress(400, 1000))

  // Paused for an urgent download, it still waits in the queue and resumes by itself.
  private val preempted =
    DownloadState.Paused(DownloadProgress(400, 1000), PauseReason.Preempted("urgent"))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 1000)

  @Test
  fun keyAction_eachChordOnEachRow_runsItsAction() {
    val space = KeyPress(Key.Spacebar)
    val enter = KeyPress(Key.Enter)
    val copyPath = KeyPress(Key.C, meta = true, alt = true)
    val retry = KeyPress(Key.R, meta = true)
    val pc = KeyboardPlatform.Pc
    val cases = listOf(
      KeyCase(space, downloading, RowAction.Pause),
      KeyCase(space, DownloadState.Queued, RowAction.Pause),
      KeyCase(space, paused, RowAction.Resume),
      KeyCase(space, preempted, RowAction.Pause),
      KeyCase(space, DownloadState.Failed(KetchError.Http(503)), RowAction.Retry),
      // A failure that cannot resume starts over.
      KeyCase(space, DownloadState.Failed(KetchError.Http(416)), RowAction.DownloadAgain),
      KeyCase(space, DownloadState.Canceled, RowAction.DownloadAgain),
      KeyCase(space, completed, null),
      KeyCase(enter, completed, RowAction.Open),
      KeyCase(enter, completed, RowAction.Details, device = RemoteDevice),
      KeyCase(enter, downloading, RowAction.Details),
      KeyCase(KeyPress(Key.Enter, meta = true), completed, RowAction.ShowInFolder),
      KeyCase(KeyPress(Key.Enter, ctrl = true), completed, RowAction.ShowInFolder, pc),
      KeyCase(KeyPress(Key.Backspace), completed, RowAction.Remove),
      KeyCase(KeyPress(Key.Delete), downloading, RowAction.Remove, pc),
      KeyCase(KeyPress(Key.Backspace, shift = true), paused, RowAction.Remove),
      KeyCase(KeyPress(Key.C, meta = true), downloading, RowAction.CopyLink),
      KeyCase(copyPath, completed, RowAction.CopyPath),
      KeyCase(copyPath, downloading, null),
      KeyCase(retry, paused, RowAction.Resume),
      KeyCase(retry, preempted, null),
      KeyCase(retry, DownloadState.Failed(KetchError.Network()), RowAction.Retry),
      KeyCase(retry, downloading, null),
    )
    for (case in cases) {
      val row = row(case.state, case.device)
      assertEquals(case.expected, action(case.press, row, case.platform), "$case")
    }
  }

  @Test
  fun listKey_arrowsAndJumps_moveTheFocus() {
    assertEquals(ListKey.Move(-1), key(KeyPress(Key.DirectionUp)))
    assertEquals(ListKey.Move(1, extend = true), key(KeyPress(Key.DirectionDown, shift = true)))
    assertEquals(ListKey.Move(Int.MIN_VALUE), key(KeyPress(Key.MoveHome)))
    assertEquals(ListKey.Move(Int.MAX_VALUE), key(KeyPress(Key.MoveEnd)))
    assertEquals(ListKey.Page(1), key(KeyPress(Key.PageDown)))
  }

  @Test
  fun listKey_selectionAndTuningKeys_mapToTheirCommands() {
    assertEquals(ListKey.SelectAll, key(KeyPress(Key.A, meta = true)))
    assertEquals(ListKey.Escape, key(KeyPress(Key.Escape)))
    assertEquals(ListKey.Connections(1), key(KeyPress(Key.Equals)))
    assertEquals(ListKey.Connections(-1), key(KeyPress(Key.Minus)))
    assertEquals(ListKey.Priority(1), key(KeyPress(Key.DirectionUp, meta = true, alt = true)))
    assertEquals(ListKey.Inspect, key(KeyPress(Key.DirectionRight)))
  }

  @Test
  fun stepPriority_staysBetweenLowAndHigh() {
    assertEquals(DownloadPriority.HIGH, stepPriority(DownloadPriority.NORMAL, 1))
    assertEquals(DownloadPriority.HIGH, stepPriority(DownloadPriority.HIGH, 1))
    assertEquals(DownloadPriority.LOW, stepPriority(DownloadPriority.LOW, -1))
    assertEquals(DownloadPriority.NORMAL, stepPriority(DownloadPriority.HIGH, -1))
  }

  @Test
  fun stepPriority_fromUrgent_onlyStepsDown() {
    assertEquals(DownloadPriority.URGENT, stepPriority(DownloadPriority.URGENT, 1))
    assertEquals(DownloadPriority.HIGH, stepPriority(DownloadPriority.URGENT, -1))
  }

  @Test
  fun apply_spaceOnSelectionWithRunningRows_pausesThemAndNamesTheRest() = actionsTest { f ->
    val one = f.add(downloading)
    val two = f.add(downloading)
    val three = f.add(paused)
    val rows = listOf(one, two, three).map { rowOf(it) }
    val selection = ListSelection(f.state)
    selection.update(SelectionState().selectAllVisible(rows.map { it.key }))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Rows(KetchCommands.TogglePause), rows)
    runCurrent()

    assertEquals(listOf("pause"), one.calls)
    assertEquals(listOf("pause"), two.calls)
    assertTrue(three.calls.isEmpty())
    assertEquals("Paused 2 downloads · 1 already paused", f.messages().last().title.load())
  }

  @Test
  fun apply_shiftDeleteOnPausedRow_opensRemoveWithTheBoxChecked() = actionsTest { f ->
    val rows = listOf(rowOf(f.add(paused)))
    val selection = ListSelection(f.state)
    selection.update(SelectionState().select(rows.single().key))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Rows(KetchCommands.RemoveAndTrash), rows)

    assertEquals(RowDialog.Remove(rows, withFiles = true), f.runner.dialog)
  }

  @Test
  fun apply_deleteOnSelection_removesAtOnceWithoutAsking() = actionsTest { f ->
    val rows = List(2) { rowOf(f.add(downloading)) }
    backgroundScope.launch { f.state.tasks.collect {} }
    runCurrent()
    val selection = ListSelection(f.state)
    selection.update(SelectionState().selectAllVisible(rows.map { it.key }))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Rows(KetchCommands.Remove), rows)
    runCurrent()

    assertEquals(null, f.runner.dialog)
    assertTrue(f.state.tasks.value.isEmpty())
  }

  @Test
  fun apply_moveWithShift_extendsTheSelectionFromTheAnchor() = actionsTest { f ->
    val rows = List(4) { rowOf(f.add(downloading)) }
    val selection = ListSelection(f.state)
    selection.update(SelectionState().select(rows[1].key))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Move(1, extend = true), rows)
    keyboard.apply(ListKey.Move(1, extend = true), rows)

    assertEquals(rows.drop(1).map { it.key }.toSet(), f.state.selectedKeys)
    assertEquals(rows[3].key, selection.focusedKey)
  }

  @Test
  fun apply_escapeWithNothingSelected_focusesSearch() = actionsTest { f ->
    val requests = mutableListOf<Unit>()
    backgroundScope.launch { f.state.focusSearchRequests.collect { requests += it } }
    runCurrent()
    val keyboard = ListKeyboard(ListSelection(f.state), f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Escape, emptyList())
    runCurrent()

    assertEquals(1, requests.size)
  }

  @Test
  fun apply_connectionsPressedThreeTimes_commitsOnceAfterThePause() = actionsTest { f ->
    val task = f.add(downloading, DownloadRequest("https://example.com/a.iso", connections = 4))
    val rows = listOf(rowOf(task))
    val selection = ListSelection(f.state)
    selection.update(SelectionState().select(rows.single().key))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    repeat(3) { keyboard.apply(ListKey.Connections(1), rows) }
    advanceTimeBy(300.milliseconds)
    runCurrent()
    assertTrue(task.calls.isEmpty())
    advanceTimeBy(200.milliseconds)
    runCurrent()

    assertEquals(listOf("connections 7"), task.calls)
  }

  @Test
  fun apply_connectionsOnAutoRow_stepsFromTheConnectionsItOpened() = actionsTest { f ->
    val task = f.add(downloading, DownloadRequest("https://example.com/a.iso"))
    task.segments.value = List(4) { Segment(it, it * 100L, it * 100L + 99) }
    val rows = listOf(rowOf(task))
    val selection = ListSelection(f.state)
    selection.update(SelectionState().select(rows.single().key))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Connections(1), rows)
    advanceTimeBy(500.milliseconds)
    runCurrent()

    assertEquals(listOf("connections 5"), task.calls)
  }

  @Test
  fun apply_connectionsOnAutoTorrent_leavesThePeerLimitAlone() = actionsTest { f ->
    val task = f.add(downloading, DownloadRequest("magnet:?xt=urn:btih:abc"))
    val rows = listOf(rowOf(task))
    val selection = ListSelection(f.state)
    selection.update(SelectionState().select(rows.single().key))
    val keyboard = ListKeyboard(selection, f.runner, RowMenuState(), backgroundScope)

    keyboard.apply(ListKey.Connections(1), rows)
    advanceTimeBy(500.milliseconds)
    runCurrent()

    assertTrue(task.calls.isEmpty())
  }

  private data class KeyCase(
    val press: KeyPress,
    val state: DownloadState,
    val expected: RowAction?,
    val platform: KeyboardPlatform = KeyboardPlatform.Mac,
    val device: DeviceInfo = ListFixtures.device,
  )

  private fun row(state: DownloadState, device: DeviceInfo = ListFixtures.device): TaskRow =
    ListFixtures.row(
      id = "t",
      state = state,
      request = DownloadRequest("https://example.com/a.iso"),
      device = device,
    )

  private fun action(
    press: KeyPress,
    row: TaskRow,
    platform: KeyboardPlatform = KeyboardPlatform.Mac,
  ): RowAction? {
    val command = ShortcutMatcher(platform).match(press, ShortcutContext(listFocused = true))
      ?: return null
    val menu = taskActions(row.request, row.state, row.device).menu
    return keyAction(command, row, menu)
  }

  private fun key(press: KeyPress): ListKey? {
    val command = ShortcutMatcher(KeyboardPlatform.Mac)
      .match(press, ShortcutContext(listFocused = true)) ?: return null
    return listKey(command)
  }
}
