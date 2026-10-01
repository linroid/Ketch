package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable

/** The system dialogs that choose a download folder and `.torrent` files on this device. */
interface FilePicker {
  /** Whether [pickFolder] can choose a folder here; the web cannot. */
  val canPickFolder: Boolean

  /**
   * Asks the user for a folder to save downloads in.
   *
   * @param initialFolder folder to start in, where the platform allows it.
   * @return a file path, or on Android a `content://` tree URI that Ketch keeps permission to
   *   write; `null` when the user cancels or [canPickFolder] is `false`.
   */
  suspend fun pickFolder(initialFolder: String? = null): String?

  /**
   * Asks the user for `.torrent` files. Their content is read only on demand, like dropped files.
   *
   * @return the files chosen, empty when the user cancels.
   */
  suspend fun pickTorrentFiles(): List<DroppedFile>
}

/** The [FilePicker] of this platform. */
@Composable
expect fun rememberFilePicker(): FilePicker
