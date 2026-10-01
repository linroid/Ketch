package com.linroid.ketch.app.state

import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Largest `.torrent` file the apps read, matching the torrent source's metainfo bound. Platform
 * readers stop one byte past it so an oversized file is reported without being loaded.
 */
const val MAX_TORRENT_FILE_BYTES: Int = 4 * 1024 * 1024

/**
 * A download handed to the app from outside, such as a file opened from the file manager or a
 * magnet link opened from a browser.
 */
sealed interface IncomingDownload {
  /** What the user opened, such as the file name. */
  val label: String

  /**
   * A `.torrent` file's [content], resolved like a dropped file through
   * [com.linroid.ketch.api.KetchApi.resolveContent] so remote backends work too.
   */
  class Ready(override val label: String, val content: ByteArray) : IncomingDownload {
    // Content equality lets opening the same file twice queue it only once.
    override fun equals(other: Any?): Boolean =
      other is Ready && label == other.label && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * label.hashCode() + content.contentHashCode()

    override fun toString(): String = "Ready(label=$label, ${content.size} bytes)"
  }

  /**
   * Links handed to the app, such as a magnet link opened from a browser or text shared from
   * another app, for the add sheet.
   *
   * @property urls the links, in the order they arrived.
   * @property source how they reached the app.
   */
  data class Links(val urls: List<String>, val source: LinkSource) : IncomingDownload {
    override val label: String
      get() = urls.singleOrNull() ?: "${urls.size} links"
  }

  /** The input could not be read; [message] explains why. */
  data class Failed(override val label: String, val message: String) : IncomingDownload
}

/** How [IncomingDownload.Links] reached the app. */
enum class LinkSource {
  /** Launch arguments, such as a magnet link a desktop OS passes to the launcher. */
  Arguments,

  /**
   * A link the app is registered to open, such as a `magnet:` link opened through the OS, a URL
   * scheme or the web app's protocol handler.
   */
  OpenUrl,

  /** Text shared from another app or selected and sent to Ketch. */
  Share,
}

/**
 * Downloads opened from outside the app. Platform entry points offer them as they arrive, even
 * before the UI is ready. Each one stays [pending] (links in [pendingLinks]) until the UI
 * [completes][complete] it, so a UI recreated in between (e.g. an Android configuration change)
 * shows it again; keep this object for as long as that UI can be recreated.
 */
class IncomingDownloads {
  private val pendingState = MutableStateFlow<List<IncomingDownload.Ready>>(emptyList())
  private val linksState = MutableStateFlow<List<IncomingDownload.Links>>(emptyList())
  private val failureChannel = Channel<IncomingDownload.Failed>(Channel.UNLIMITED)

  /** Opened downloads waiting for the user, oldest first. */
  val pending: StateFlow<List<IncomingDownload.Ready>> = pendingState.asStateFlow()

  /** Links handed to the app waiting for the add sheet, oldest first. */
  val pendingLinks: StateFlow<List<IncomingDownload.Links>> = linksState.asStateFlow()

  /** Files that could not be read, each delivered once. Collect from one place only. */
  val failures: Flow<IncomingDownload.Failed> = failureChannel.receiveAsFlow()

  /** Adds [download]; one that is already pending is not added twice. */
  fun offer(download: IncomingDownload) {
    when (download) {
      is IncomingDownload.Ready -> pendingState.update { if (download in it) it else it + download }
      is IncomingDownload.Links -> linksState.update { if (download in it) it else it + download }
      is IncomingDownload.Failed -> failureChannel.trySend(download)
    }
  }

  /** Marks [download] as handled, whether the user downloaded or dismissed it. */
  fun complete(download: IncomingDownload.Ready) {
    pendingState.update { it - download }
  }

  /** Marks [links] as handled, whether the user added or dismissed them. */
  fun complete(links: IncomingDownload.Links) {
    linksState.update { it - links }
  }

  /** Offers [urls] that arrived through [source]; blank ones are dropped. */
  fun offerLinks(urls: List<String>, source: LinkSource) {
    val links = urls.map { it.trim() }.filter { it.isNotEmpty() }
    if (links.isNotEmpty()) offer(IncomingDownload.Links(links, source))
  }

  /**
   * Offers every link in [text], such as text shared from another app, found as the add sheet
   * finds them ([LinkParser.parseIntake], with ranges expanded).
   *
   * @return whether [text] holds a link; when it does not, nothing is offered.
   */
  fun offerText(text: String, source: LinkSource): Boolean {
    val urls = LinkParser.parseIntake(text).links().map { it.url }
    offerLinks(urls, source)
    return urls.isNotEmpty()
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

/** Checks `.torrent` file contents read by a platform entry point. */
fun torrentFileDownload(name: String, bytes: ByteArray): IncomingDownload = when {
  bytes.isEmpty() -> IncomingDownload.Failed(name, "The file is empty")
  bytes.size > MAX_TORRENT_FILE_BYTES -> IncomingDownload.Failed(
    name,
    "The file is larger than ${MAX_TORRENT_FILE_BYTES / 1024 / 1024} MiB",
  )
  else -> IncomingDownload.Ready(name, bytes)
}
