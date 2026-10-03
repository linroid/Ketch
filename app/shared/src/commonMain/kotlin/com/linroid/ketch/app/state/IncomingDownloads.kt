package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.LinkKind
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.links
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.intake_file_empty
import ketch.app.shared.generated.resources.intake_file_too_large
import ketch.app.shared.generated.resources.intake_file_unreadable
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
 * magnet link opened from a browser, or a link that pairs another device.
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

  /**
   * A link that pairs this app with another device, such as `ketch://pair?host=…#token=…` from a
   * scanned code. Its token sits in the fragment, which [label] and [toString] leave out.
   *
   * @property link the link as it arrived, token included.
   */
  class Pairing(val link: String) : IncomingDownload {
    override val label: String
      get() = link.substringBefore('#')

    override fun equals(other: Any?): Boolean = other is Pairing && link == other.link

    override fun hashCode(): Int = link.hashCode()

    override fun toString(): String = "Pairing($label)"
  }

  /** The input could not be read; [reason] explains why. */
  data class Failed(override val label: String, val reason: UiText) : IncomingDownload {
    /** A failure explained by [message] from elsewhere, such as an exception's. */
    constructor(label: String, message: String) : this(label, verbatim(message))
  }
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
 * Whether [link] pairs this app with another device: a `ketch://pair` link, whose query names
 * the device and whose fragment holds its token.
 */
fun isPairingLink(link: String): Boolean = PAIRING_LINK.containsMatchIn(link.trim())

private val PAIRING_LINK = Regex("^ketch://pair(?:[/?#]|$)", RegexOption.IGNORE_CASE)

/**
 * Downloads opened from outside the app. Platform entry points offer them as they arrive, even
 * before the UI is ready. Each one stays [pending] (links in [pendingLinks], pairing links in
 * [pendingPairings]) until the UI [completes][complete] it, so a UI recreated in between (e.g. an
 * Android configuration change) shows it again; keep this object for as long as that UI can be
 * recreated.
 */
class IncomingDownloads {
  private val pendingState = MutableStateFlow<List<IncomingDownload.Ready>>(emptyList())
  private val linksState = MutableStateFlow<List<IncomingDownload.Links>>(emptyList())
  private val pairingState = MutableStateFlow<List<IncomingDownload.Pairing>>(emptyList())
  private val failureChannel = Channel<IncomingDownload.Failed>(Channel.UNLIMITED)

  /** Opened downloads waiting for the user, oldest first. */
  val pending: StateFlow<List<IncomingDownload.Ready>> = pendingState.asStateFlow()

  /** Links handed to the app waiting for the add sheet, oldest first. */
  val pendingLinks: StateFlow<List<IncomingDownload.Links>> = linksState.asStateFlow()

  /** Pairing links waiting for the user to confirm them, oldest first. */
  val pendingPairings: StateFlow<List<IncomingDownload.Pairing>> = pairingState.asStateFlow()

  /** Whether anything opened from outside the app waits for the user. */
  val hasPending: Boolean
    get() = pendingState.value.isNotEmpty() || linksState.value.isNotEmpty() ||
      pairingState.value.isNotEmpty()

  /** Files that could not be read, each delivered once. Collect from one place only. */
  val failures: Flow<IncomingDownload.Failed> = failureChannel.receiveAsFlow()

  /** Adds [download]; one that is already pending is not added twice. */
  fun offer(download: IncomingDownload) {
    when (download) {
      is IncomingDownload.Ready -> pendingState.update { if (download in it) it else it + download }
      is IncomingDownload.Links -> linksState.update { if (download in it) it else it + download }
      is IncomingDownload.Pairing ->
        pairingState.update { if (download in it) it else it + download }
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

  /** Marks [pairing] as handled, whether the user connected or declined. */
  fun complete(pairing: IncomingDownload.Pairing) {
    pairingState.update { it - pairing }
  }

  /** Offers [urls] that arrived through [source]; blank ones are dropped. */
  fun offerLinks(urls: List<String>, source: LinkSource) {
    val links = urls.map { it.trim() }.filter { it.isNotEmpty() }
    if (links.isNotEmpty()) offer(IncomingDownload.Links(links, source))
  }

  /**
   * Offers one link the app was opened with, by its scheme: a pairing link ([isPairingLink]) as
   * [IncomingDownload.Pairing], and a link Ketch downloads, such as a `magnet:` link, as
   * [IncomingDownload.Links].
   *
   * @return whether [link] was offered; other links, such as other `ketch:` links, are not.
   */
  fun offerLink(link: String, source: LinkSource): Boolean {
    val trimmed = link.trim()
    when {
      isPairingLink(trimmed) -> offer(IncomingDownload.Pairing(trimmed))
      LinkKind.of(trimmed) == LinkKind.Other -> return false
      else -> offerLinks(listOf(trimmed), source)
    }
    return true
  }

  /**
   * Offers every link in [text], such as text shared from another app, found as the add sheet
   * finds them ([LinkParser.parseIntake], with ranges expanded). Pairing links among them are
   * offered as [IncomingDownload.Pairing].
   *
   * @return whether [text] holds a link; when it does not, nothing is offered.
   */
  fun offerText(text: String, source: LinkSource): Boolean {
    val (pairings, urls) = LinkParser.parseIntake(text).links().map { it.url }
      .partition(::isPairingLink)
    pairings.forEach { offer(IncomingDownload.Pairing(it)) }
    offerLinks(urls, source)
    return pairings.isNotEmpty() || urls.isNotEmpty()
  }

  /** Offers the contents of a `.torrent` file named [name]. */
  fun offerTorrentFile(name: String, bytes: ByteArray) {
    offer(torrentFileDownload(name, bytes))
  }

  /** Reports that the file named [name] could not be read. */
  fun offerUnreadable(name: String, cause: Throwable) {
    val reason = cause.message?.let(::verbatim) ?: Res.string.intake_file_unreadable.text()
    offer(IncomingDownload.Failed(name, reason))
  }
}

/** Checks `.torrent` file contents read by a platform entry point. */
fun torrentFileDownload(name: String, bytes: ByteArray): IncomingDownload = when {
  bytes.isEmpty() -> IncomingDownload.Failed(name, Res.string.intake_file_empty.text())
  bytes.size > MAX_TORRENT_FILE_BYTES -> IncomingDownload.Failed(
    name,
    Res.string.intake_file_too_large.text(MAX_TORRENT_FILE_BYTES / 1024 / 1024),
  )
  else -> IncomingDownload.Ready(name, bytes)
}
