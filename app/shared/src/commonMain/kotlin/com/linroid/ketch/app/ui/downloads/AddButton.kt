package com.linroid.ketch.app.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.components.AddButtonMode
import com.linroid.ketch.app.components.KetchAddButton
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.LocalWindowDrop
import com.linroid.ketch.app.ui.shell.DeviceDrag
import com.linroid.ketch.app.ui.shell.deviceDropTarget
import com.linroid.ketch.app.ui.sidebar.rememberDevices
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.urlHost
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_clip_download
import ketch.app.shared.generated.resources.downloads_clip_download_from

/**
 * The Downloads header's Add button ([KetchAddButton]). It opens the add sheet; with a link on
 * the clipboard, read only where that shows no notice, it becomes a split button that adds the
 * link at once; while a drag from another app hovers the window it is a drop target that adds as
 * a drop on the window does, to the device quick add picks. New downloads' lanes fly from it.
 */
@Composable
internal fun AddButton(state: AppState, modifier: Modifier = Modifier) {
  val clipboard = rememberPageClipboard()
  val link = rememberClipboardLink(state, clipboard, skipOffered = true)
  val windowDrop = LocalWindowDrop.current
  var over by remember { mutableStateOf(false) }
  val description = (link as? ClipboardLink.Found)
    ?.let { clipDescription(clipName(it.url), urlHost(it.url)).resolve() }
    .orEmpty()
  val mode = addButtonMode(
    link = link,
    dragging = windowDrop?.active == true,
    over = over,
    description = description,
  )
  KetchAddButton(
    mode = mode,
    onAdd = { state.openIntake() },
    onAddClip = { (link as? ClipboardLink.Found)?.let { addClipboardLink(state, it) } },
    onOpenSheet = { state.openIntake() },
    addTooltip = KetchCommands.Add.label.resolve(),
    shortcut = KetchCommands.Add.shortcutLabel(),
    clipShortcut = KetchCommands.AddClipboardLink.shortcutLabel(),
    modifier = modifier
      .addFlightOrigin()
      .addDropTarget(state, onOver = { over = it }),
  )
}

/**
 * What the Add button offers: a drop target while [dragging] (lit while the drag is [over] it),
 * the copied link when the clipboard was read and holds one Ketch does not have, else the plain
 * button. A link only probably on the clipboard ([ClipboardLink.Maybe]) keeps the plain button,
 * since reading it would show the platform's notice.
 *
 * @param description what adding the copied link does, [clipDescription] in the screen's
 *   language.
 */
internal fun addButtonMode(
  link: ClipboardLink,
  dragging: Boolean,
  over: Boolean,
  description: String = "",
): AddButtonMode = when {
  dragging -> AddButtonMode.Drop(over)
  link is ClipboardLink.Found -> AddButtonMode.Clip(
    name = clipName(link.url),
    description = description,
    id = link.hash,
  )
  else -> AddButtonMode.Plain
}

/** The file name a copied [url] downloads to, as rows name it. */
internal fun clipName(url: String): String = displayName(DownloadRequest(url = url))

/** "Download ubuntu.iso from releases.ubuntu.com", or without the site when it is the name. */
internal fun clipDescription(name: String, host: String?): UiText =
  if (host == null || host == name) {
    Res.string.downloads_clip_download.text(name)
  } else {
    Res.string.downloads_clip_download_from.text(name, host)
  }

/**
 * Makes the button take drops for the device quick add picks, as the window does around it,
 * reporting whether a drag of links or files is [over][onOver] it. Without a device to add to,
 * drops fall through to the window.
 */
@Composable
private fun Modifier.addDropTarget(state: AppState, onOver: (Boolean) -> Unit): Modifier {
  val devices = rememberDevices(state)
  val targetId = state.quickAddTarget()?.deviceId
  val device = devices.firstOrNull { it.deviceId == targetId } ?: return this
  return deviceDropTarget(
    state = state,
    device = device,
    onHover = { onOver(it == DeviceDrag.Content) },
    acceptsRows = false,
  )
}
