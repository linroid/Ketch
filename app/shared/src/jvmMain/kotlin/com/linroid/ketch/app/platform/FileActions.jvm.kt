package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException

@Composable
actual fun rememberFileActions(): FileActions? = DesktopFileActions

/** The operating systems the desktop app runs on. */
internal enum class DesktopOs {
  MacOs,
  Windows,
  Linux;

  companion object {
    /** The system this JVM runs on. */
    val current: DesktopOs = of(System.getProperty("os.name").orEmpty())

    /** The system named [osName], as in `os.name`; other Unix systems count as Linux. */
    fun of(osName: String): DesktopOs = when {
      osName.startsWith("Mac", ignoreCase = true) -> MacOs
      osName.startsWith("Windows", ignoreCase = true) -> Windows
      else -> Linux
    }
  }
}

/** How [FileActions.reveal] shows a file in the file manager. */
internal sealed interface RevealCommand {
  /** Runs [command], which opens the file manager with the file selected. */
  data class Run(val command: List<String>) : RevealCommand

  /** Selects [file] through `Desktop.browseFileDirectory`. */
  data class Browse(val file: File) : RevealCommand

  /** Opens [folder], when the file itself cannot be selected or is gone. */
  data class OpenFolder(val folder: File) : RevealCommand
}

/** Menu label of [FileActions.reveal] on [os]. */
internal fun revealLabel(os: DesktopOs): String = when (os) {
  DesktopOs.MacOs -> "Show in Finder"
  DesktopOs.Windows -> "Show in Explorer"
  DesktopOs.Linux -> "Show in folder"
}

/**
 * How to show [file] in the file manager of [os]. A file that is gone shows the closest folder
 * that still exists.
 *
 * @param canBrowse whether `Desktop.browseFileDirectory` is supported, which Linux desktops
 *   rarely do.
 */
internal fun revealCommand(os: DesktopOs, file: File, canBrowse: Boolean): RevealCommand {
  if (!file.exists()) {
    val folder = generateSequence(file.absoluteFile.parentFile) { it.parentFile }
      .firstOrNull { it.isDirectory }
      ?: throw FileActionException("${file.name} and its folder were moved or deleted")
    return RevealCommand.OpenFolder(folder)
  }
  return when (os) {
    DesktopOs.MacOs -> RevealCommand.Run(listOf("open", "-R", file.path))
    // Explorer reads "/select," and the path as separate arguments, so spaces need no quotes.
    DesktopOs.Windows -> RevealCommand.Run(listOf("explorer.exe", "/select,", file.path))
    DesktopOs.Linux -> if (canBrowse) {
      RevealCommand.Browse(file)
    } else {
      RevealCommand.OpenFolder(file.absoluteFile.parentFile ?: file)
    }
  }
}

/** Opens files through `java.awt.Desktop`, falling back to system commands where it cannot. */
internal object DesktopFileActions : FileActions {
  private val os = DesktopOs.current

  override val revealLabel: String = revealLabel(os)

  override val canShare: Boolean = false

  override val canTrash: Boolean by lazy { supports(Desktop.Action.MOVE_TO_TRASH) }

  override suspend fun open(path: String) = withContext(Dispatchers.IO) {
    openFile(existingFile(path))
  }

  override suspend fun reveal(path: String) = withContext(Dispatchers.IO) {
    val file = File(path)
    when (val command = revealCommand(os, file, supports(Desktop.Action.BROWSE_FILE_DIR))) {
      is RevealCommand.Run -> run(command.command, "Couldn't show ${file.name}")
      is RevealCommand.Browse -> Desktop.getDesktop().browseFileDirectory(command.file)
      is RevealCommand.OpenFolder -> openFile(command.folder)
    }
  }

  override suspend fun share(path: String) {
    throw FileActionException("Sharing files isn't available on this computer")
  }

  override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
    File(path).exists()
  }

  override suspend fun moveToTrash(path: String) = withContext(Dispatchers.IO) {
    val file = existingFile(path)
    val trash = if (os == DesktopOs.Windows) "Recycle Bin" else "Trash"
    if (!canTrash) throw FileActionException("This computer has no $trash for Ketch to use")
    if (!Desktop.getDesktop().moveToTrash(file)) {
      throw FileActionException("Couldn't move ${file.name} to the $trash")
    }
  }

  private fun existingFile(path: String): File {
    val file = File(path)
    if (!file.exists()) throw FileActionException("${file.name} was moved or deleted")
    return file
  }

  private fun openFile(file: File) {
    when {
      supports(Desktop.Action.OPEN) -> try {
        Desktop.getDesktop().open(file)
      } catch (e: IOException) {
        throw FileActionException("No app can open ${file.name}", e)
      }
      os == DesktopOs.Linux -> run(listOf("xdg-open", file.path), "No app can open ${file.name}")
      else -> throw FileActionException("This computer can't open ${file.name}")
    }
  }

  /** Starts [command] without waiting: Explorer exits with 1 even when it worked. */
  private fun run(command: List<String>, failure: String) {
    try {
      ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    } catch (e: IOException) {
      throw FileActionException(failure, e)
    }
  }

  private fun supports(action: Desktop.Action): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)
}
