package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
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

  override suspend fun pickFolder(initialFolder: String?): String? =
    withContext(Dispatchers.Swing) {
      if (DesktopOs.current == DesktopOs.MacOs) pickMacFolder(initialFolder)
      else chooseFolder(initialFolder)
    }

  override suspend fun pickTorrentFiles(): List<DroppedFile> = withContext(Dispatchers.Swing) {
    val dialog = fileDialog("Open torrent files")
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
    // Windows ignores filename filters but takes a pattern as the file name.
    if (DesktopOs.current == DesktopOs.Windows) dialog.file = "*.torrent"
    dialog.isVisible = true
    dialog.files.filter { it.isFile }.map { it.toDroppedFile() }
  }

  private fun pickMacFolder(initialFolder: String?): String? {
    System.setProperty(MAC_FOLDER_DIALOG, "true")
    try {
      val dialog = fileDialog("Choose a download folder")
      dialog.directory = initialFolder
      dialog.isVisible = true
      val name = dialog.file ?: return null
      return File(dialog.directory, name).path
    } finally {
      System.clearProperty(MAC_FOLDER_DIALOG)
    }
  }

  private fun chooseFolder(initialFolder: String?): String? {
    val chooser = JFileChooser(initialFolder)
    chooser.dialogTitle = "Choose a download folder"
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    val result = chooser.showDialog(focusedWindow(), "Choose")
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.path else null
  }

  private fun fileDialog(title: String): FileDialog = when (val owner = focusedWindow()) {
    is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
    else -> FileDialog(owner as? Frame, title, FileDialog.LOAD)
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
