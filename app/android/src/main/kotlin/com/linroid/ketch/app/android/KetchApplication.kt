package com.linroid.ketch.app.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.io.IOException

class KetchApplication : Application() {

  /**
   * Files opened with Ketch. Held here rather than in the activity so a file stays pending
   * across activity recreation, such as a rotation while its dialog is showing.
   */
  val incoming = IncomingDownloads()

  /**
   * The app's log files, kept for bug reports and shared from Settings → About. Held here so
   * the process has one writer, even when the service is recreated.
   */
  val fileLogger: FileLogger by lazy {
    FileLogger(
      fileSystem = FileSystem.SYSTEM,
      directory = filesDir.toOkioPath() / "logs",
      dispatcher = Dispatchers.IO,
      minLevel = LogLevel.DEBUG,
    )
  }

  // Reads outlive the activity that received the file, so a rotation cannot cancel them.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    try {
      startForegroundService(Intent(this, KetchService::class.java))
    } catch (e: IllegalStateException) {
      // Android 12+ throws ForegroundServiceStartNotAllowedException when the process starts in
      // the background, such as for another app opening a shared download. The activity
      // creates the service when it opens.
      fileLogger.w("[KetchApplication] Couldn't start the service: ${e.describeCauses()}")
    }
  }

  /** Reads a `.torrent` file opened with Ketch from a file manager or another app. */
  fun openFile(uri: Uri) {
    scope.launch {
      val name = displayName(uri)
      try {
        incoming.offerTorrentFile(name, readAtMost(uri, MAX_TORRENT_FILE_BYTES + 1))
      } catch (e: Exception) {
        incoming.offerUnreadable(name, e)
      }
    }
  }

  private fun displayName(uri: Uri): String {
    val queried = try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
      // Not every provider answers queries, e.g. file: URIs.
      null
    }
    return queried ?: uri.lastPathSegment ?: "torrent"
  }

  /** Reads up to [limit] bytes; a longer file is cut off there and rejected by its size. */
  private fun readAtMost(uri: Uri, limit: Int): ByteArray {
    val input = contentResolver.openInputStream(uri)
      ?: throw IOException("The file could not be opened")
    return input.use {
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (output.size() < limit) {
        val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
      }
      output.toByteArray()
    }
  }
}
