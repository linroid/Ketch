package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
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
import com.linroid.ketch.app.state.taskActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListKeyboardTest {
  private val downloading = ListFixtures.downloading(400)
  private val paused = DownloadState.Paused(DownloadProgress(400, 1000))
  private val completed = DownloadState.Completed("/downloads/a.iso", totalBytes = 1000)

  @Test
  fun keyAction_spaceOnDownloadingRow_isPause() {
    assertEquals(RowAction.Pause, action(KeyPress(Key.Spacebar), row(downloading)))
  }

  @Test
  fun keyAction_spaceOnQueuedRow_isPause() {
    assertEquals(RowAction.Pause, action(KeyPress(Key.Spacebar), row(DownloadState.Queued)))
  }

  @Test
  fun keyAction_spaceOnPausedRow_isResume() {
    assertEquals(RowAction.Resume, action(KeyPress(Key.Spacebar), row(paused)))
  }

  @Test
  fun keyAction_spaceOnFailedRow_isRetry() {
    val failed = DownloadState.Failed(KetchError.Http(503))

    assertEquals(RowAction.Retry, action(KeyPress(Key.Spacebar), row(failed)))
  }

  @Test
  fun keyAction_spaceOnFailureThatCannotResume_isDownloadAgain() {
    val failed = DownloadState.Failed(KetchError.Http(416))

    assertEquals(RowAction.DownloadAgain, action(KeyPress(Key.Spacebar), row(failed)))
  }

  @Test
  fun keyAction_spaceOnCanceledRow_isDownloadAgain() {
    val canceled = row(DownloadState.Canceled)

    assertEquals(RowAction.DownloadAgain, action(KeyPress(Key.Spacebar), canceled))
  }

  @Test
  fun keyAction_spaceOnCompletedRow_doesNothing() {
    assertNull(action(KeyPress(Key.Spacebar), row(completed)))
  }

  @Test
  fun keyAction_enterOnCompletedRow_opensTheFile() {
    assertEquals(RowAction.Open, action(KeyPress(Key.Enter), row(completed)))
  }

  @Test
  fun keyAction_enterOnRemoteCompletedRow_showsDetails() {
    val remote = row(completed, device = RemoteDevice)

    assertEquals(RowAction.Details, action(KeyPress(Key.Enter), remote))
  }

  @Test
  fun keyAction_enterOnDownloadingRow_showsDetails() {
    assertEquals(RowAction.Details, action(KeyPress(Key.Enter), row(downloading)))
  }

  @Test
  fun keyAction_commandEnterOnCompletedRow_showsTheFileInItsFolder() {
    val press = KeyPress(Key.Enter, meta = true)

    assertEquals(RowAction.ShowInFolder, action(press, row(completed)))
  }

  @Test
  fun keyAction_ctrlEnterOnPc_showsTheFileInItsFolder() {
    val press = KeyPress(Key.Enter, ctrl = true)

    assertEquals(RowAction.ShowInFolder, action(press, row(completed), KeyboardPlatform.Pc))
  }

  @Test
  fun keyAction_backspaceOnMac_removesFromTheList() {
    assertEquals(RowAction.Remove, action(KeyPress(Key.Backspace), row(completed)))
  }

  @Test
  fun keyAction_deleteOnPc_removesFromTheList() {
    val press = KeyPress(Key.Delete)

    assertEquals(RowAction.Remove, action(press, row(downloading), KeyboardPlatform.Pc))
  }

  @Test
  fun keyAction_shiftBackspaceOnPausedRow_asksToRemoveWithFiles() {
    val press = KeyPress(Key.Backspace, shift = true)

    assertEquals(RowAction.RemoveAndDelete, action(press, row(paused)))
  }

  @Test
  fun keyAction_commandC_copiesTheLink() {
    assertEquals(RowAction.CopyLink, action(KeyPress(Key.C, meta = true), row(downloading)))
  }

  @Test
  fun keyAction_optionCommandCOnCompletedRow_copiesThePath() {
    val press = KeyPress(Key.C, meta = true, alt = true)

    assertEquals(RowAction.CopyPath, action(press, row(completed)))
    assertNull(action(press, row(downloading)))
  }

  @Test
  fun keyAction_commandROnPausedRow_resumes() {
    assertEquals(RowAction.Resume, action(KeyPress(Key.R, meta = true), row(paused)))
  }

  @Test
  fun keyAction_commandROnFailedRow_retries() {
    val failed = DownloadState.Failed(KetchError.Network())

    assertEquals(RowAction.Retry, action(KeyPress(Key.R, meta = true), row(failed)))
    assertNull(action(KeyPress(Key.R, meta = true), row(downloading)))
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
  fun listKey_menuOpen_doesNothing() {
    val matcher = ShortcutMatcher(KeyboardPlatform.Mac)
    val context = ShortcutContext(listFocused = true, menuOpen = true)

    assertNull(matcher.match(KeyPress(Key.Spacebar), context))
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
    assertEquals("Paused 2 downloads · 1 already paused", f.messages().last().title)
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

  private fun row(state: DownloadState, device: DeviceInfo = LocalDevice): TaskRow =
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
