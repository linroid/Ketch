package com.linroid.ketch.app.state

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlin.io.encoding.Base64

/**
 * Largest `.torrent` file the apps read, matching the torrent source's metainfo bound. Platform
 * readers stop one byte past it so an oversized file is reported without being loaded.
 */
const val MAX_TORRENT_FILE_BYTES: Int = 4 * 1024 * 1024

/** A download handed to the app from outside, such as a file opened from the file manager. */
sealed interface IncomingDownload {
  /** What the user opened, such as the file name. */
  val label: String

  /**
   * Ready to resolve. [url] may be an inline `data:` URL that is never shown to the user;
   * backends resolve it to a short URL for the task.
   */
  data class Ready(override val label: String, val url: String) : IncomingDownload

  /** The input could not be read; [message] explains why. */
  data class Failed(override val label: String, val message: String) : IncomingDownload
}

/**
 * Downloads opened from outside the app. Platform entry points offer them as they arrive, even
 * before the UI is ready. Each one stays [pending] until the UI [completes][complete] it, so a
 * UI recreated in between (e.g. an Android configuration change) shows it again; keep this
 * object for as long as that UI can be recreated.
 */
class IncomingDownloads {
  private val pendingState = MutableStateFlow<List<IncomingDownload.Ready>>(emptyList())
  private val failureChannel = Channel<IncomingDownload.Failed>(Channel.UNLIMITED)

  /** Opened downloads waiting for the user, oldest first. */
  val pending: StateFlow<List<IncomingDownload.Ready>> = pendingState.asStateFlow()

  /** Files that could not be read, each delivered once. Collect from one place only. */
  val failures: Flow<IncomingDownload.Failed> = failureChannel.receiveAsFlow()

  /** Adds [download]; one that is already pending is not added twice. */
  fun offer(download: IncomingDownload) {
    when (download) {
      is IncomingDownload.Ready -> pendingState.update { if (download in it) it else it + download }
      is IncomingDownload.Failed -> failureChannel.trySend(download)
    }
  }

  /** Marks [download] as handled, whether the user downloaded or dismissed it. */
  fun complete(download: IncomingDownload.Ready) {
    pendingState.update { it - download }
  }

  /** Offers the contents of a `.torrent` file named [name]. */
  fun offerTorrentFile(name: String, bytes: ByteArray) {
    offer(torrentFileDownload(name, bytes))
  }

  /** Reports that the file named [name] could not be read. */
  fun offerUnreadable(name: String, cause: Throwable) {
    offer(IncomingDownload.Failed(name, cause.message ?: "The file could not be read"))
  }
}

/**
 * Wraps `.torrent` file contents in a `data:` URL so they reach local and remote backends
 * alike: a remote daemon cannot read this device's files.
 */
fun torrentFileDownload(name: String, bytes: ByteArray): IncomingDownload = when {
  bytes.isEmpty() -> IncomingDownload.Failed(name, "The file is empty")
  bytes.size > MAX_TORRENT_FILE_BYTES -> IncomingDownload.Failed(
    name,
    "The file is larger than ${MAX_TORRENT_FILE_BYTES / 1024 / 1024} MiB",
  )
  else -> IncomingDownload.Ready(
    name,
    "data:application/x-bittorrent;base64,${Base64.Default.encode(bytes)}",
  )
}
