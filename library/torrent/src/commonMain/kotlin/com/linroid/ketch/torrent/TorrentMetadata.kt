package com.linroid.ketch.torrent

/**
 * Parsed torrent metadata extracted from a .torrent file or
 * fetched via magnet link metadata exchange.
 *
 * @property infoHash the info hash identifying this torrent
 * @property name torrent name from the info dictionary
 * @property pieceLength size of each piece in bytes
 * @property totalBytes total size across all files
 * @property files list of files in this torrent
 * @property trackers list of tracker announce URLs
 * @property comment optional comment from the torrent creator
 * @property createdBy optional tool that created the torrent
 */
internal data class TorrentMetadata(
  val infoHash: InfoHash,
  val name: String,
  val pieceLength: Long,
  val totalBytes: Long,
  val files: List<TorrentFile>,
  val trackers: List<String> = emptyList(),
  val comment: String? = null,
  val createdBy: String? = null,
) {
  /**
   * A single file within a torrent.
   *
   * @property index zero-based file index
   * @property path relative path within the torrent directory
   * @property size file size in bytes
   */
  data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
  )

  companion object {
    /**
     * Parses a bencoded .torrent file into [TorrentMetadata].
     *
     * @throws IllegalArgumentException if the torrent data is
     *   malformed or missing required fields
     */
    @Suppress("UNCHECKED_CAST")
    fun fromBencode(data: ByteArray): TorrentMetadata {
      val root = Bencode.decode(data) as? Map<String, Any>
        ?: throw IllegalArgumentException(
          "Torrent root must be a dictionary"
        )

      val info = root["info"] as? Map<String, Any>
        ?: throw IllegalArgumentException(
          "Missing 'info' dictionary"
        )

      val name = (info["name"] as? ByteArray)?.decodeToString()
        ?: throw IllegalArgumentException(
          "Missing 'name' in info"
        )

      val pieceLength = info["piece length"] as? Long
        ?: throw IllegalArgumentException(
          "Missing 'piece length' in info"
        )

      // Calculate info hash from the raw bencoded info dict
      val infoEncoded = Bencode.encode(info)
      val infoHash = sha1Digest(infoEncoded)

      val files = parseFiles(info, name)
      val totalBytes = files.sumOf { it.size }

      val trackers = parseTrackers(root)

      val comment = (root["comment"] as? ByteArray)
        ?.decodeToString()
      val createdBy = (root["created by"] as? ByteArray)
        ?.decodeToString()

      return TorrentMetadata(
        infoHash = InfoHash.fromBytes(infoHash),
        name = name,
        pieceLength = pieceLength,
        totalBytes = totalBytes,
        files = files,
        trackers = trackers,
        comment = comment,
        createdBy = createdBy,
      )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFiles(
      info: Map<String, Any>,
      name: String,
    ): List<TorrentFile> {
      val filesList = info["files"] as? List<Map<String, Any>>
      return if (filesList != null) {
        // Multi-file torrent
        filesList.mapIndexed { index, fileDict ->
          val pathParts = (fileDict["path"] as? List<ByteArray>)
            ?.map { it.decodeToString() }
            ?: throw IllegalArgumentException(
              "Missing 'path' in file entry $index"
            )
          val size = fileDict["length"] as? Long
            ?: throw IllegalArgumentException(
              "Missing 'length' in file entry $index"
            )
          TorrentFile(
            index = index,
            path = (listOf(name) + pathParts).joinToString("/"),
            size = size,
          )
        }
      } else {
        // Single-file torrent
        val length = info["length"] as? Long
          ?: throw IllegalArgumentException(
            "Missing 'length' in single-file torrent"
          )
        listOf(TorrentFile(index = 0, path = name, size = length))
      }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTrackers(
      root: Map<String, Any>,
    ): List<String> {
      val announceList =
        root["announce-list"] as? List<List<ByteArray>>
      if (announceList != null) {
        return announceList.flatMap { tier ->
          tier.map { it.decodeToString() }
        }
      }
      val announce = (root["announce"] as? ByteArray)
        ?.decodeToString()
      return if (announce != null) listOf(announce) else emptyList()
    }
  }
}
