package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Files named by launch arguments: plain paths from Windows file associations, or `file:` URIs
 * from Linux desktop entries. Paths are made absolute so another process can open them.
 */
internal fun fileArguments(args: List<String>): List<File> = args.mapNotNull { arg ->
  when {
    arg.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(arg)) }.getOrNull()
    arg.isBlank() || arg.startsWith("-") -> null
    else -> File(arg)
  }?.absoluteFile
}

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
