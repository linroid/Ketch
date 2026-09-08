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
  val infoBytes: ByteArray = ByteArray(0),
  val pieceHashes: ByteArray = ByteArray(0),
  val trackerTiers: List<List<String>> = trackers.map { listOf(it) },
  val isPrivate: Boolean = false,
  val metainfoBytes: ByteArray = ByteArray(0),
  val isMultiFile: Boolean = files.size > 1,
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
    fun fromBencode(data: ByteArray, maxBytes: Int = 4 * 1024 * 1024): TorrentMetadata {
      val root = Bencode.parse(data, maxBytes)
      requireNotNull(root.dictionary) { "Torrent root must be a dictionary" }
      val info = requireNotNull(root["info"]) { "Missing info dictionary" }
      requireNotNull(info.dictionary) { "Info must be a dictionary" }
      require(info["meta version"] == null) { "BitTorrent v2/hybrid is not supported" }
      val name = requireNotNull(info["name"]?.text()) { "Missing name" }
      validatePathComponent(name)
      val pieceLength = requireNotNull(info["piece length"]?.integer) { "Missing piece length" }
      require(pieceLength in 1..16L * 1024 * 1024) { "Unsupported piece length" }
      val fileNodes = info["files"]
      require(fileNodes == null || info["length"] == null) { "Conflicting file layouts" }
      val files = if (fileNodes != null) {
        val list = requireNotNull(fileNodes.list) { "Invalid file list" }
        require(list.isNotEmpty() && list.size <= 10_000) { "Invalid file count" }
        list.mapIndexed { index, file ->
          require(file["attr"]?.text()?.contains('l') != true) { "Symlink files unsupported" }
          val parts = requireNotNull(file["path"]?.list) { "Missing file path" }
          require(parts.isNotEmpty() && parts.size <= 64) { "Invalid path depth" }
          val names = parts.map { requireNotNull(it.text()) { "Invalid path component" } }
          names.forEach(::validatePathComponent)
          val size = requireNotNull(file["length"]?.integer) { "Missing file length" }
          require(size >= 0) { "Negative file length" }
          TorrentFile(index, (listOf(name) + names).joinToString("/"), size)
        }
      } else {
        val size = requireNotNull(info["length"]?.integer) { "Missing file length" }
        require(size >= 0) { "Negative file length" }
        listOf(TorrentFile(0, name, size))
      }
      val paths = files.map { canonicalTorrentName(it.path).lowercase() }.toSet()
      require(paths.size == files.size) { "Colliding torrent paths" }
      for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
          require(parent !in paths) { "File/directory collision" }
          parent = parent.substringBeforeLast('/', "")
        }
      }
      var total = 0L
      for (file in files) {
        require(file.size <= Long.MAX_VALUE - total) { "Torrent size overflow" }
        total += file.size
      }
      val pieces = requireNotNull(info["pieces"]?.bytes) { "Missing piece hashes" }
      val count = total / pieceLength + if (total % pieceLength == 0L) 0 else 1
      require(pieces.size % 20 == 0 && count == pieces.size.toLong() / 20) {
        "Piece hash count does not match file layout"
      }
      val tiers = root["announce-list"]?.let { node ->
        requireNotNull(node.list) { "Invalid tracker tiers" }.map { tier ->
          requireNotNull(tier.list) { "Invalid tracker tier" }.map { tracker ->
            requireNotNull(tracker.text()) { "Invalid tracker URL" }
          }.distinct()
        }.filter { it.isNotEmpty() }
      } ?: root["announce"]?.text()?.let { listOf(listOf(it)) }.orEmpty()
      require(tiers.sumOf { it.size } <= 128) { "Too many trackers" }
      val privateFlag = info["private"]?.integer
      require(info["private"] == null || privateFlag == 0L || privateFlag == 1L) {
        "Invalid private flag"
      }
      val rawInfo = data.copyOfRange(info.start, info.end)
      return TorrentMetadata(
        infoHash = InfoHash.fromBytes(sha1Digest(rawInfo)),
        name = name,
        pieceLength = pieceLength,
        totalBytes = total,
        files = files,
        trackers = tiers.flatten(),
        comment = root["comment"]?.text(),
        createdBy = root["created by"]?.text(),
        infoBytes = rawInfo,
        pieceHashes = pieces,
        trackerTiers = tiers,
        isPrivate = privateFlag == 1L,
        metainfoBytes = data.copyOf(),
        isMultiFile = fileNodes != null,
      )
    }

    internal fun validatePathComponent(value: String) {
      require(value.isNotEmpty() && value != "." && value != "..") { "Unsafe torrent path" }
      require(value.encodeToByteArray().size <= 255 && value.none { it < ' ' || it in "/\\:*?\"<>|" }) {
        "Unsafe torrent path component"
      }
      require(!value.endsWith('.') && !value.endsWith(' ')) { "Unsafe trailing path character" }
      val stem = value.substringBefore('.').uppercase()
      require(stem !in setOf("CON", "PRN", "AUX", "NUL") &&
        !(stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) &&
          stem.last() in '1'..'9')) { "Reserved torrent path" }
    }
  }
}

/** Filesystem collision key; hashes use the original encoded bytes. */
internal expect fun canonicalTorrentName(value: String): String
