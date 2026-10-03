package com.linroid.ketch.app.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.UnreadableFile
import com.linroid.ketch.app.feedback.UnreadableFiles
import com.linroid.ketch.app.i18n.initAppLanguage
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.state.DiscoverHistoryStore
import com.linroid.ketch.app.state.FileDiscoverHistoryStore
import com.linroid.ketch.app.state.IncomingDownloads
import com.linroid.ketch.app.state.MAX_TORRENT_FILE_BYTES
import com.linroid.ketch.config.FileConfigStore
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
   * The app's log files, kept for bug reports and shared from Settings → About; backups leave
   * them out, as they hold Discover searches and links. Held here so the process has one
   * writer, even when the service is recreated.
   */
  val fileLogger: FileLogger by lazy {
    FileLogger(
      fileSystem = FileSystem.SYSTEM,
      directory = filesDir.toOkioPath() / "logs",
      dispatcher = Dispatchers.IO,
      minLevel = LogLevel.DEBUG,
    )
  }

  /** Files the app or the service could not read and moved aside, which the app reports. */
  val unreadableFiles = UnreadableFiles()

  /**
   * The app's settings in `config.toml`, shared by the app and the service. Whichever reads a file
   * that cannot be read first, usually [onCreate] for the language, moves it aside and reports it
   * to [unreadableFiles].
   */
  val configStore: FileConfigStore by lazy {
    FileConfigStore(filesDir.resolve("config.toml").absolutePath) { unreadable ->
      fileLogger.w(
        "[KetchApplication] Moved unreadable settings aside to ${unreadable.movedTo}: " +
          unreadable.cause.describeCauses()
      )
      unreadableFiles.report(UnreadableFile(UnreadableFile.Kind.Settings, unreadable.movedTo))
    }
  }

  /**
   * The Discover sessions, kept in `discover-history.json`, which backups leave out. Held here
   * so the process has one writer, as the main screen builds a new controller each time it binds
   * the service.
   */
  val discoverHistory: DiscoverHistoryStore by lazy {
    FileDiscoverHistoryStore(
      fileSystem = FileSystem.SYSTEM,
      path = filesDir.toOkioPath() / DISCOVER_HISTORY_FILE,
      dispatcher = Dispatchers.IO,
    )
  }

  // Reads outlive the activity that received the file, so a rotation cannot cancel them.
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    initAppLanguage(this, savedLanguage())
    // The main screen loads the Discover history on the main thread once it binds the service;
    // read now, meanwhile, the history is in memory by then.
    scope.launch { discoverHistory.load() }
    try {
      startForegroundService(Intent(this, KetchService::class.java))
    } catch (e: IllegalStateException) {
      // Android 12+ throws ForegroundServiceStartNotAllowedException when the process starts in
      // the background, such as for another app opening a shared download. The activity
      // creates the service when it opens.
      fileLogger.w("[KetchApplication] Couldn't start the service: ${e.describeCauses()}")
    }
  }

  // The language chosen in Settings, which the app applies itself before Android 13.
  private fun savedLanguage(): String? = try {
    configStore.load().appearance.language
  } catch (e: Exception) {
    fileLogger.w("[KetchApplication] Couldn't read the language: ${e.describeCauses()}")
    null
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

/** File of the Discover history in `filesDir`; the backup rules in `res/xml` name it too. */
private const val DISCOVER_HISTORY_FILE = "discover-history.json"
