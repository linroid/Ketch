package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.i18n.UiText

/**
 * Opens, reveals and shares the files that downloads saved on this device.
 *
 * A path is a task's `DownloadState.Completed.outputPath`: a file, the folder of a multi-file
 * torrent, or an Android `content://` document URI. Actions may be called from the main thread,
 * as they move blocking work off it, and throw [FileActionException] when the platform refuses.
 */
interface FileActions {
  /**
   * Label of [reveal] in menus: "Show in Finder", "Show in Explorer", "Show in Files" or
   * "Show in folder"; `null` where files cannot be revealed, as on Android.
   */
  val revealLabel: UiText?

  /** Whether [share] can hand files to other apps here. */
  val canShare: Boolean

  /** Whether [moveToTrash] works here; elsewhere removed files can only be deleted. */
  val canTrash: Boolean

  /** Opens the file at [path] in the app the system picks for it. */
  suspend fun open(path: String)

  /**
   * Shows the file at [path] in the file manager, selected where the platform allows it. When
   * the file is gone, shows the closest folder that still exists. Needs a [revealLabel].
   */
  suspend fun reveal(path: String)

  /** Offers the file at [path] to other apps through the share sheet. Needs [canShare]. */
  suspend fun share(path: String)

  /** Whether the file or folder at [path] still exists. */
  suspend fun exists(path: String): Boolean

  /** Moves the file or folder at [path] to the Trash. Needs [canTrash]. */
  suspend fun moveToTrash(path: String)
}

/**
 * A [FileActions] call that the platform refused.
 *
 * @param message what went wrong, as a sentence to show, such as "No app can open ubuntu.iso".
 */
class FileActionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The [FileActions] of this platform, or `null` where downloaded files are out of reach (web). */
@Composable
expect fun rememberFileActions(): FileActions?
