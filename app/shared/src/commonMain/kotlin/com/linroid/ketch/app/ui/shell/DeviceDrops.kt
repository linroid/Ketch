package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.platform.LocalWindowInfo
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.platform.DragExitEffect
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.rememberFileDropReader
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.LocalWindowDrop
import com.linroid.ketch.app.ui.devices.dropFiles
import com.linroid.ketch.app.ui.downloads.actions.draggedTaskKeys
import com.linroid.ketch.app.ui.pulse.diskLabel
import kotlinx.coroutines.launch

/** What a drag over a device carries, as far as can be told before it is dropped. */
@Immutable
internal sealed interface DeviceDrag {
  /** Links, magnets, `.torrent` files or lists of links from another app. */
  data object Content : DeviceDrag

  /** Rows dragged out of the Downloads list. */
  data class Rows(val keys: List<TaskKey>) : DeviceDrag
}

/**
 * What a device under a drag says it will do with it.
 *
 * @property accepts whether dropping there does anything; otherwise the drop is turned down.
 */
@Immutable
internal data class DropHint(val text: String, val accepts: Boolean)

/**
 * What [device] does with [drag]: content adds there while it is reachable, and rows are sent
 * there from the other devices, or moved while [move] (⌥) is held.
 *
 * @param moveKey the key that moves rows, as the keyboard names it, which the hint mentions;
 *   `null` leaves it out, as on touch.
 */
internal fun dropHint(
  drag: DeviceDrag,
  device: DevicePresence,
  move: Boolean,
  moveKey: String? = null,
): DropHint {
  if (!device.connected || !device.health.isOnline) return DropHint("Not reachable now", false)
  return when (drag) {
    DeviceDrag.Content -> {
      val free = device.disk?.let { " · ${diskLabel(it)}" }.orEmpty()
      DropHint("Drop here$free", true)
    }
    is DeviceDrag.Rows -> {
      val outgoing = drag.keys.count { it.deviceId != device.deviceId }
      val what = if (outgoing == 1) "it" else outgoing.toString()
      when {
        outgoing == 0 -> DropHint("Already here", false)
        move -> DropHint("Move $what here", true)
        moveKey == null -> DropHint("Send $what here", true)
        else -> DropHint("Send $what here · $moveKey moves", true)
      }
    }
  }
}

/**
 * Adds [text] dropped on [target] as a paste does: one plain link at once while quick add is on,
 * anything else in the add sheet aimed at [target]. The Downloads list shows when [target] is
 * one of the devices it lists.
 */
internal fun AppState.dropTextOn(target: InstanceEntry, text: String) {
  when (val add = pasteAction(text, quickAddsLinks)) {
    is ClipboardAdd.Now -> quickAdd(add.urls, target)
    is ClipboardAdd.Sheet -> {
      openIntake(IntakeRequest(text = add.text, targetDeviceId = target.deviceId))
    }
    ClipboardAdd.Empty -> return
  }
  if (target in shownInstances.value) showDownloads(statusFilter)
}

/**
 * Adds [files] dropped on [target]: a `.torrent` file opens the add sheet resolving it there,
 * and lists of links fill the sheet.
 */
internal fun AppState.dropFilesOn(target: InstanceEntry, files: List<DroppedFile>) {
  dropFiles(target, files)
  if (target in shownInstances.value) showDownloads(statusFilter)
}

/**
 * Makes this element take drags for [device]: links and files from other apps add there (see
 * [dropTextOn] and [dropFilesOn]); rows from the Downloads list are sent there while
 * [acceptsRows], or moved with ⌥ held. A drop [dropHint] turns down, such as onto a device that
 * cannot be reached, does nothing. While content hovers here the window's drop overlay stays.
 *
 * @param onHover what hovers over it now, or `null` once the drag leaves.
 * @param hoverKey what it reports to the window's [com.linroid.ketch.app.ui.DropHoverState]
 *   while content hovers it; by default the target itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.deviceDropTarget(
  state: AppState,
  device: DevicePresence,
  onHover: (DeviceDrag?) -> Unit = {},
  acceptsRows: Boolean = true,
  hoverKey: Any? = null,
): Modifier {
  val reader = rememberFileDropReader()
  val scope = rememberCoroutineScope()
  val window = LocalWindowInfo.current
  val windowDrop = LocalWindowDrop.current
  val currentDevice by rememberUpdatedState(device)
  val currentOnHover by rememberUpdatedState(onHover)
  val currentWindowDrop by rememberUpdatedState(windowDrop)
  val target = remember(state, reader, scope, window, hoverKey) {
    object : DragAndDropTarget {
      // Rows are told apart when the drag arrives, while their keys can still be read.
      private var drag: DeviceDrag? = null

      private fun hover(next: DeviceDrag?) {
        drag = next
        currentOnHover(next)
        val key = hoverKey ?: this
        if (next == DeviceDrag.Content) currentWindowDrop?.enter(key)
        else currentWindowDrop?.exit(key)
      }

      override fun onEntered(event: DragAndDropEvent) {
        val keys = draggedTaskKeys(event)
        hover(if (keys.isEmpty()) DeviceDrag.Content else DeviceDrag.Rows(keys))
      }

      override fun onExited(event: DragAndDropEvent) = hover(null)

      override fun onEnded(event: DragAndDropEvent) = hover(null)

      override fun onDrop(event: DragAndDropEvent): Boolean {
        val dropped = drag ?: DeviceDrag.Content
        hover(null)
        val device = currentDevice
        val move = window.keyboardModifiers.isAltPressed
        if (!dropHint(dropped, device, move).accepts) return false
        if (dropped is DeviceDrag.Rows) {
          state.sendTo(state.tasksOf(dropped.keys), device.entry, move = move)
          return true
        }
        val files = reader.files(event)
        if (files.isNotEmpty()) {
          state.dropFilesOn(device.entry, files)
          return true
        }
        val text = reader.text(event) ?: return false
        scope.launch {
          val read = text()
          if (read.isNotBlank()) state.dropTextOn(device.entry, read)
        }
        return true
      }
    }
  }
  DragExitEffect { currentOnHover(null) }
  return dragAndDropTarget(
    shouldStartDragAndDrop = { event ->
      reader.accepts(event) || acceptsRows && draggedTaskKeys(event).isNotEmpty()
    },
    target = target,
  )
}
