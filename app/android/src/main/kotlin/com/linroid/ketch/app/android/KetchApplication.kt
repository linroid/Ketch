package com.linroid.ketch.app.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException

class KetchApplication : Application() {

  /**
   * Files opened with Ketch. Held here rather than in the activity so a file stays pending
   * across activity recreation, such as a rotation while its dialog is showing.
   */
  val incoming = IncomingDownloads()

  // Reads outlive the activity that received the file, so a rotation cannot cancel them.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    startForegroundService(Intent(this, KetchService::class.java))
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
