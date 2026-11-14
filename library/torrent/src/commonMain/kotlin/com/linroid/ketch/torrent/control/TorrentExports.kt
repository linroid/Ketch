package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.api.TorrentExport
import com.linroid.ketch.torrent.encodeBase64
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentIdentity
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.sourceTrackerTiers

/** The most bencode values a `.torrent` file Ketch accepted can hold. */
private const val MAX_METAINFO_NODES = 1_000_000

/**
 * What [com.linroid.ketch.torrent.api.TorrentController.export] answers for the task [taskId]:
 * from its [metainfo] when the task holds it, else from its [url] when that is a magnet link;
 * `null` when neither identifies the torrent, as before a `.torrent` link is fetched.
 *
 * The magnet link names the torrent's own trackers and web seeds, never the extra trackers of
 * the settings, the peers the task's own link named or its file selection. A private torrent
 * gets none: its trackers may carry a passkey, and no peer would send its metadata.
 */
internal fun exportOf(
  taskId: String,
  url: String,
  metainfo: ByteArray?,
  includeMetainfo: Boolean,
  config: TorrentConfig,
): TorrentExport? {
  if (metainfo == null) {
    val magnet = try {
      MagnetUri.parse(url)
    } catch (_: IllegalArgumentException) {
      return null
    }
    return TorrentExport(
      taskId = taskId,
      name = magnet.displayName,
      infoHash = magnet.identity.v1?.hex,
      infoHashV2 = magnet.identity.v2?.hex,
      magnet = magnet.copy(explicitPeers = emptyList(), selectOnly = emptySet()).toUri(),
    )
  }
  val described = describe(metainfo, config)
  return TorrentExport(
    taskId = taskId,
    name = described.name,
    infoHash = described.identity.v1?.hex,
    infoHashV2 = described.identity.v2?.hex,
    privateTorrent = described.privateTorrent,
    magnet = if (described.privateTorrent) {
      null
    } else {
      MagnetUri(
        identity = described.identity,
        displayName = described.name,
        trackers = sourceTrackerTiers(metainfo, config.maxMetadataBytes).flatten().distinct(),
        webSeeds = described.webSeeds,
      ).toUri()
    },
    metainfo = if (includeMetainfo) encodeBase64(metainfo) else null,
  )
}

private class Described(
  val identity: TorrentIdentity,
  val name: String?,
  val privateTorrent: Boolean,
  val webSeeds: List<String>,
)

/** Reads [metainfo] as v2 or hybrid when its info names a version, else as v1. */
private fun describe(metainfo: ByteArray, config: TorrentConfig): Described {
  val envelope = Bencode.parse(metainfo, config.maxMetadataBytes, MAX_METAINFO_NODES)
  if (envelope["info"]?.get("meta version") != null) {
    val document = TorrentV2Document.parse(
      metainfo,
      maxDocumentBytes = config.maxMetadataBytes,
      maxFiles = config.maxFilesPerTorrent,
    )
    return Described(
      identity = document.identity,
      name = document.info.displayName?.utf8()?.takeIf { it.isNotEmpty() },
      privateTorrent = document.info.privateTorrent,
      webSeeds = document.webSeeds,
    )
  }
  val metadata = TorrentMetadata.fromBencode(metainfo, config.maxMetadataBytes)
  return Described(
    identity = TorrentIdentity(v1 = metadata.infoHash),
    name = metadata.name.takeIf { it.isNotEmpty() },
    privateTorrent = metadata.isPrivate,
    webSeeds = metadata.webSeeds,
  )
}
