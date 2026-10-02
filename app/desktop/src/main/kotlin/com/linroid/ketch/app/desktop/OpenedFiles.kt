package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI

/** Launch argument that starts the app hidden in the menu bar or notification area. */
internal const val BACKGROUND_FLAG = "--background"

/**
 * What launch arguments ask the app to open.
 *
 * @property files `.torrent` files, made absolute so another process can open them.
 * @property links links for the add sheet, such as a `magnet:` link a browser opened.
 */
internal data class OpenedArguments(
  val files: List<File> = emptyList(),
  val links: List<String> = emptyList(),
) {
  /** The arguments that hand these to another instance of the app. */
  fun toArguments(): List<String> = files.map { it.path } + links
}

/**
 * Sorts launch arguments into files and links. Files are plain paths from Windows file
 * associations or `file:` URIs from Linux desktop entries; `magnet:`, `http(s):` and `ftp(s):`
 * arguments, which the OS passes for links Ketch is registered to open, are links. Flags and
 * URIs of other schemes are left out.
 */
internal fun fileArguments(args: List<String>): OpenedArguments {
  val files = ArrayList<File>()
  val links = ArrayList<String>()
  for (arg in args) {
    when {
      arg.isBlank() || arg.startsWith("-") -> continue
      LINK_ARGUMENT.containsMatchIn(arg) -> links += arg
      arg.startsWith("file:", ignoreCase = true) ->
        runCatching { File(URI(arg)) }.getOrNull()?.let { files += it.absoluteFile }
      // Two letters at least, so a Windows drive such as C: stays a path.
      URI_SCHEME.containsMatchIn(arg) -> continue
      else -> files += File(arg).absoluteFile
    }
  }
  return OpenedArguments(files, links)
}

private val LINK_ARGUMENT = Regex("^(magnet:|https?://|ftps?://)", RegexOption.IGNORE_CASE)
private val URI_SCHEME = Regex("^[a-z][a-z0-9+.-]+:", RegexOption.IGNORE_CASE)

/** Reads each file as a `.torrent`, stopping one byte past the size the apps accept. */
internal fun IncomingDownloads.offerFiles(files: List<File>) {
  for (file in files) {
    try {
      val bytes = file.inputStream().use { it.readNBytes(MAX_TORRENT_FILE_BYTES + 1) }
      offerTorrentFile(file.name, bytes)
    } catch (e: IOException) {
      offerUnreadable(file.name, e)
    }
  }
}

/**
 * macOS sends files opened from Finder as events rather than arguments, including the one that
 * launched the app. Events that arrive before this runs are queued by the JDK.
 */
internal fun installOpenFileHandler(onFiles: (List<File>) -> Unit) {
  if (!Desktop.isDesktopSupported()) return
  val desktop = Desktop.getDesktop()
  if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
    desktop.setOpenFileHandler { event -> onFiles(event.files) }
  }
}
