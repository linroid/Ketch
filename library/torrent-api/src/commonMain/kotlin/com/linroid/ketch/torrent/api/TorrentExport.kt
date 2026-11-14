package com.linroid.ketch.torrent.api

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable

/**
 * A task's torrent in the forms other clients open: its magnet link and, when asked for, its
 * `.torrent` file; see [TorrentController.export].
 *
 * @property name the torrent's name, or `null` when it names none or is not known yet
 * @property infoHash the v1 info hash as 40 hex characters; `null` for a v2-only torrent
 * @property infoHashV2 the v2 info hash as 64 hex characters; `null` for a v1-only torrent
 * @property privateTorrent whether the torrent is private (BEP 27): its peers never share its
 *   metadata, so it has no [magnet]
 * @property magnet the magnet link, with the torrent's name, trackers and web seeds; `null` for
 *   a private torrent, whose trackers may carry a passkey and which a magnet could not open
 * @property metainfo the `.torrent` file as Base64, when asked for and known: a magnet still
 *   looking for its metadata has none. [metainfoBytes] decodes it
 */
@Serializable
data class TorrentExport(
  val taskId: String,
  val name: String? = null,
  val infoHash: String? = null,
  val infoHashV2: String? = null,
  val privateTorrent: Boolean = false,
  val magnet: String? = null,
  val metainfo: String? = null,
) {
  /** The bytes of the `.torrent` file, or `null` without [metainfo]. */
  fun metainfoBytes(): ByteArray? = metainfo?.let(Base64.Default::decode)

  /** Leaves the `.torrent` file out, as it may be megabytes and trackers may carry passkeys. */
  override fun toString(): String =
    "TorrentExport(taskId=$taskId, infoHash=$infoHash, infoHashV2=$infoHashV2, " +
      "privateTorrent=$privateTorrent, metainfo=${metainfo?.length ?: 0} chars)"
}
