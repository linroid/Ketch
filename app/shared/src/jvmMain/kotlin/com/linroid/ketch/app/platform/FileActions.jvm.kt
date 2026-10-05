package com.linroid.ketch.app.platform

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.reveal_in_explorer
import ketch.app.shared.generated.resources.reveal_in_finder
import ketch.app.shared.generated.resources.reveal_in_folder
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

/** How [FileActions.open] hands a file to the operating system. */
internal sealed interface OpenCommand {
  data class Run(val command: List<String>) : OpenCommand

  data class Desktop(val file: File) : OpenCommand
}

internal fun openCommand(os: DesktopOs, file: File, canOpen: Boolean): OpenCommand = when {
  // AWT rejects executable types on Windows with "Unsupported URI content", even when
  // OPEN is supported. Explorer handles file associations and installer elevation itself.
  // Explorer also treats commas as delimiters. Quote the absolute path explicitly because
  // ProcessBuilder does not quote paths containing commas alone. No command shell is involved.
  os == DesktopOs.Windows -> OpenCommand.Run(listOf("explorer.exe", "\"${file.absolutePath}\""))
  canOpen -> OpenCommand.Desktop(file)
  os == DesktopOs.Linux -> OpenCommand.Run(listOf("xdg-open", file.path))
  else -> throw FileActionException("This computer can't open ${file.name}")
}

/** Menu label of [FileActions.reveal] on [os]. */
internal fun revealLabel(os: DesktopOs): UiText = when (os) {
  DesktopOs.MacOs -> Res.string.reveal_in_finder.text()
  DesktopOs.Windows -> Res.string.reveal_in_explorer.text()
  DesktopOs.Linux -> Res.string.reveal_in_folder.text()
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

/** Opens files through Explorer on Windows and `java.awt.Desktop` or system commands elsewhere. */
internal object DesktopFileActions : FileActions {
  private val os = DesktopOs.current

  override val revealLabel: UiText = revealLabel(os)

  override val canShare: Boolean = false

  override val canTrash: Boolean by lazy { supports(Desktop.Action.MOVE_TO_TRASH) }

  override suspend fun open(path: String) = withContext(Dispatchers.IO) {
    openFile(existingFile(path))
  }

  override suspend fun reveal(path: String) = withContext(Dispatchers.IO) {
    val file = File(path)
    when (val command = revealCommand(os, file, supports(Desktop.Action.BROWSE_FILE_DIR))) {
      is RevealCommand.Run -> run(command.command, "Couldn't show ${file.name}")
      is RevealCommand.Browse -> attempt("Couldn't show ${file.name}") {
        Desktop.getDesktop().browseFileDirectory(command.file)
      }
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
    val failure = "Couldn't move ${file.name} to the $trash"
    if (!attempt(failure) { Desktop.getDesktop().moveToTrash(file) }) {
      throw FileActionException(failure)
    }
  }

  private fun existingFile(path: String): File {
    val file = File(path)
    if (!file.exists()) throw FileActionException("${file.name} was moved or deleted")
    return file
  }

  private fun openFile(file: File) {
    when (val command = openCommand(os, file, supports(Desktop.Action.OPEN))) {
      is OpenCommand.Desktop -> attempt("No app can open ${file.name}") {
        Desktop.getDesktop().open(command.file)
      }
      is OpenCommand.Run -> run(command.command, "Couldn't open ${file.name}")
    }
  }

  /** Starts [command] without waiting: Explorer exits with 1 even when it worked. */
  private fun run(command: List<String>, failure: String) {
    attempt(failure) {
      ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    }
  }

  /** Runs [action] and reports its I/O and runtime failures as [failure]. */
  private inline fun <T> attempt(failure: String, action: () -> T): T = try {
    action()
  } catch (e: IOException) {
    throw FileActionException(failure, e)
  } catch (e: RuntimeException) {
    // The file vanished meanwhile, or the system refused the action after all.
    throw FileActionException(failure, e)
  }

  private fun supports(action: Desktop.Action): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)
}
