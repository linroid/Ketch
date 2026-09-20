package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import okio.Path

/** Validates the format before dispatch so hybrid content never falls back to v1 parsing. */
internal fun resolveV2Metainfo(
  url: String?,
  bytes: ByteArray,
  config: TorrentConfig,
  privacy: TorrentDiscoveryPrivacy,
): ResolvedSource? {
  val envelope = Bencode.parse(bytes, config.maxMetadataBytes)
  val version = envelope["info"]?.get("meta version") ?: return null
  require(version.integer == 2L) { "Unsupported torrent metainfo version" }
  val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
    maxFiles = config.maxFilesPerTorrent)
  sourceTrackerTiers(bytes, config.maxMetadataBytes)
  val mapping = TorrentOutputMapping.from(document)
  val hash = document.info.hash.hex
  val name = document.info.displayName?.utf8()?.takeIf { value ->
    try { TorrentMetadata.validatePathComponent(value); true } catch (_: Exception) { false }
  } ?: hash
  return ResolvedSource(
    url = url ?: "torrent:$hash", sourceType = TorrentDownloadSource.TYPE,
    totalBytes = document.info.totalBytes, supportsResume = true, suggestedFileName = name,
    maxSegments = mapping.files.size,
    metadata = mapOf("infoHash" to hash, "metainfo" to encodeBase64(bytes), "format" to "v2",
      "name" to name, "pieceLength" to document.info.pieceLength.toString(),
      "discoveryPrivacy" to privacy.name),
    files = mapping.files.map { file ->
      val path = file.components.joinToString("/")
      SourceFile(file.id, path, document.info.files[file.v2Index].length,
        metadata = mapOf("path" to path))
    },
    selectionMode = FileSelectionMode.MULTIPLE,
  )
}

internal fun sourceTrackerTiers(bytes: ByteArray, maxBytes: Int): List<List<String>> {
  val envelope = Bencode.parse(bytes, maxBytes)
  val tiers = envelope["announce-list"]?.let { node ->
    requireNotNull(node.list) { "Invalid tracker tiers" }.map { tier ->
      requireNotNull(tier.list) { "Invalid tracker tier" }.map { tracker ->
        requireNotNull(tracker.text()) { "Invalid tracker URL" }
      }
    }
  } ?: envelope["announce"]?.text()?.let { listOf(listOf(it)) }.orEmpty()
  return TrackerConfiguration.prepare(tiers).tiers
}

/** Stable task-bound journal lives beside the payload, including without a configured state dir. */
internal fun v2CreationLog(output: Path, taskId: String): Path =
  checkNotNull(output.parent) / ".ketch-v2-$taskId.creation"
