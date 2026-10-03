package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_pick_choose
import ketch.app.shared.generated.resources.device_pick_folder
import ketch.app.shared.generated.resources.device_pick_torrents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberFilePicker(): FilePicker = AwtFilePicker

/**
 * Native file dialogs, owned by the focused window. A folder is chosen in the native dialog on
 * macOS and in Swing's chooser elsewhere, because AWT cannot choose folders on Windows or Linux.
 */
internal object AwtFilePicker : FilePicker {
  override val canPickFolder: Boolean = true

  override suspend fun pickFolder(initialFolder: String?): String? {
    val title = Res.string.device_pick_folder.text().load()
    val choose = Res.string.device_pick_choose.text().load()
    return withContext(Dispatchers.Swing) {
      if (DesktopOs.current == DesktopOs.MacOs) pickMacFolder(initialFolder, title)
      else chooseFolder(initialFolder, title, choose)
    }
  }

  override suspend fun pickTorrentFiles(): List<DroppedFile> {
    val title = Res.string.device_pick_torrents.text().load()
    return withContext(Dispatchers.Swing) { showTorrentDialog(title) }
  }

  private fun showTorrentDialog(title: String): List<DroppedFile> =
    showFileDialog(title) { dialog ->
      dialog.isMultipleMode = true
      dialog.setFilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
      // Windows ignores filename filters but takes a pattern as the file name.
      if (DesktopOs.current == DesktopOs.Windows) dialog.file = "*.torrent"
      dialog.isVisible = true
      dialog.files.filter { it.isFile }.map { it.toDroppedFile() }
    }

  private fun pickMacFolder(initialFolder: String?, title: String): String? {
    System.setProperty(MAC_FOLDER_DIALOG, "true")
    try {
      return showFileDialog(title) { dialog ->
        dialog.directory = initialFolder
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).path }
      }
    } finally {
      System.clearProperty(MAC_FOLDER_DIALOG)
    }
  }

  private fun chooseFolder(initialFolder: String?, title: String, choose: String): String? {
    val chooser = JFileChooser(initialFolder)
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    val result = chooser.showDialog(focusedWindow(), choose)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.path else null
  }

  /** Shows a file dialog through [show], then releases its native window. */
  private fun <T> showFileDialog(title: String, show: (FileDialog) -> T): T {
    val dialog = when (val owner = focusedWindow()) {
      is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
      else -> FileDialog(owner as? Frame, title, FileDialog.LOAD)
    }
    try {
      return show(dialog)
    } finally {
      dialog.dispose()
    }
  }

  private fun focusedWindow() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

  private const val MAC_FOLDER_DIALOG = "apple.awt.fileDialogForDirectories"
}

/** [this] file as a [DroppedFile], read off the main thread with its size bounded. */
internal fun File.toDroppedFile(): DroppedFile = DroppedFile(name) { maxBytes ->
  withContext(Dispatchers.IO) {
    if (length() > maxBytes) fileTooLarge(name, maxBytes)
    // The file may grow after the length check.
    readBytes().also { if (it.size > maxBytes) fileTooLarge(name, maxBytes) }
  }
}
