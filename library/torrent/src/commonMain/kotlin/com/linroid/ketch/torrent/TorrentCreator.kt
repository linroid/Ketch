package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.torrent.crypto.merkleZeroHash
import com.linroid.ketch.torrent.crypto.newSha1
import com.linroid.ketch.torrent.crypto.newSha256
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.MAX_PIECE_LENGTH
import com.linroid.ketch.torrent.metainfo.MAX_TRACKERS
import com.linroid.ketch.torrent.metainfo.MAX_WEB_SEEDS
import com.linroid.ketch.torrent.metainfo.TorrentIdentity
import com.linroid.ketch.torrent.metainfo.V2InfoHash
import com.linroid.ketch.torrent.metainfo.isWebSeedUrl
import com.linroid.ketch.torrent.metainfo.validateTorrentComponent
import com.linroid.ketch.torrent.storage.torrentSystemFileSystem
import com.linroid.ketch.torrent.tracker.TrackerConfiguration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Which BitTorrent versions a created torrent speaks. */
enum class TorrentFormat {
  /** BitTorrent v1 (BEP 3): SHA-1 pieces over the files end to end. Every client opens it. */
  V1,

  /** BitTorrent v2 (BEP 52): a SHA-256 tree per file. Older clients cannot open it. */
  V2,

  /**
   * Both in one torrent: v1 clients and v2 clients share one swarm's files. Files are padded to
   * piece boundaries (BEP 47), which v1 clients list as `.pad` entries.
   */
  HYBRID,
}

/**
 * How [TorrentCreator.create] builds a torrent.
 *
 * @property format the versions the torrent speaks
 * @property pieceLength the piece size in bytes, a power of two from 16 KiB to 16 MiB, or 0 to
 *   pick one that gives about a thousand pieces
 * @property name the torrent's name, which clients save the content as; the file or folder's
 *   own name when `null`
 * @property trackers the tracker tiers (BEP 12): each list is a tier, tried in order. Only
 *   `http`, `https` and `udp` trackers are accepted
 * @property webSeeds `http` and `https` web seeds (BEP 19)
 * @property privateTorrent marks the torrent private (BEP 27): clients find its peers through
 *   its trackers only, so it needs one
 * @property comment a free-text comment, outside what the info hash covers
 * @property createdBy the program named as the creator; `null` for none
 * @property creationDate when it was created, in seconds since the epoch; `null` for none, which
 *   makes creating the same content twice give the same file
 * @property source a tag private trackers ask for so their torrents hash differently; it is
 *   part of the info hash
 */
data class TorrentCreateOptions(
  val format: TorrentFormat = TorrentFormat.V1,
  val pieceLength: Int = 0,
  val name: String? = null,
  val trackers: List<List<String>> = emptyList(),
  val webSeeds: List<String> = emptyList(),
  val privateTorrent: Boolean = false,
  val comment: String? = null,
  val createdBy: String? = "Ketch/${KetchApi.VERSION}",
  val creationDate: Long? = null,
  val source: String? = null,
)

/**
 * A torrent [TorrentCreator.create] built.
 *
 * @property metainfo the `.torrent` file
 * @property name the torrent's name
 * @property infoHash the v1 info hash as 40 hex characters; `null` for [TorrentFormat.V2]
 * @property infoHashV2 the v2 info hash as 64 hex characters; `null` for [TorrentFormat.V1]
 * @property magnet the magnet link; `null` for a private torrent, which a magnet cannot open
 * @property totalBytes the size of the content
 * @property pieceLength the piece size used
 * @property fileCount how many files it lists, padding aside
 */
class CreatedTorrent(
  val metainfo: ByteArray,
  val name: String,
  val infoHash: String?,
  val infoHashV2: String?,
  val magnet: String?,
  val totalBytes: Long,
  val pieceLength: Int,
  val fileCount: Int,
)

/** Builds `.torrent` files from files and folders on this device. */
object TorrentCreator {
  /**
   * Hashes the file or folder at [path] and returns its torrent. A folder's regular files are
   * listed in the order BitTorrent v2 sorts them, whatever the format; symbolic links and other
   * special files are left out. [onProgress] is told how much of the content is hashed, at most
   * once per piece. Cancelling the caller stops it.
   *
   * To seed the result, download its [CreatedTorrent.metainfo] into the folder that holds
   * [path]: the files are found complete.
   *
   * @throws IllegalArgumentException if [path] holds no content, more than 10,000 files or a
   *   name a torrent cannot carry, if an option is invalid, or if the content needs more
   *   metadata than a 4 MiB torrent file holds
   * @throws okio.IOException if a file cannot be read
   */
  suspend fun create(
    path: String,
    options: TorrentCreateOptions = TorrentCreateOptions(),
    onProgress: (hashedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
  ): CreatedTorrent = withContext(TorrentDispatchers.disk) {
    createTorrent(torrentSystemFileSystem, path.toPath(), options, onProgress)
  }
}

/** A file to hash: its [components] under the torrent's name, none for a single file. */
private class CreateEntry(val components: List<String>, val path: Path, val size: Long)

private const val BLOCK = 16 * 1024
private const val MAX_CREATED_FILES = 10_000
private const val MAX_CREATED_BYTES = 4 * 1024 * 1024
private const val TARGET_PIECES = 1024L

internal suspend fun createTorrent(
  fileSystem: FileSystem,
  path: Path,
  options: TorrentCreateOptions,
  onProgress: (hashedBytes: Long, totalBytes: Long) -> Unit,
): CreatedTorrent {
  val root = fileSystem.canonicalize(path)
  val metadata = fileSystem.metadata(root)
  val single = metadata.isRegularFile
  require(single || metadata.isDirectory) { "Only a file or a folder can become a torrent" }
  val name = options.name ?: root.name
  validateTorrentComponent(name)
  val entries = if (single) {
    listOf(CreateEntry(emptyList(), root, metadata.size ?: 0))
  } else {
    listEntries(fileSystem, root)
  }
  val totalBytes = entries.sumOf { it.size }
  require(totalBytes > 0) { "There is nothing to share: every file is empty or none was found" }
  val trackers = TrackerConfiguration.prepare(options.trackers.filter { it.isNotEmpty() }).tiers
  require(trackers.sumOf { it.size } <= MAX_TRACKERS) { "At most $MAX_TRACKERS trackers" }
  require(options.webSeeds.size <= MAX_WEB_SEEDS && options.webSeeds.all(::isWebSeedUrl)) {
    "At most $MAX_WEB_SEEDS web seeds, each an http or https URL"
  }
  require(!options.privateTorrent || trackers.isNotEmpty()) {
    "A private torrent finds peers only through its trackers, so it needs one"
  }
  val pieceLength = pieceLengthFor(options.pieceLength, totalBytes)
  val v1 = options.format != TorrentFormat.V2
  val v2 = options.format != TorrentFormat.V1
  val hybrid = v1 && v2

  // One pass over the content feeds both hashes.
  val pieces = Buffer()
  var sha1 = newSha1()
  var inPiece = 0
  fun feedV1(bytes: ByteArray, offset: Int, count: Int) {
    var at = offset
    var left = count
    while (left > 0) {
      val take = minOf(left, pieceLength - inPiece)
      sha1.update(bytes, at, take)
      inPiece += take
      at += take
      left -= take
      if (inPiece == pieceLength) {
        pieces.write(sha1.digest())
        sha1 = newSha1()
        inPiece = 0
      }
    }
  }
  val roots = arrayOfNulls<ByteArray>(entries.size)
  val layers = linkedMapOf<ByteString, ByteArray>()
  val blocksPerPiece = pieceLength / BLOCK
  val pieceLevel = blocksPerPiece.countTrailingZeroBits()
  val block = ByteArray(BLOCK)
  val zeros = ByteArray(BLOCK)
  var hashed = 0L
  for ((index, entry) in entries.withIndex()) {
    val leaves = ArrayList<ByteArray>(blocksPerPiece)
    val pieceHashes = ArrayList<ByteArray>()
    var read = 0L
    fileSystem.openReadOnly(entry.path).use { handle ->
      while (read < entry.size) {
        currentCoroutineContext().ensureActive()
        val want = minOf(BLOCK.toLong(), entry.size - read).toInt()
        var got = 0
        while (got < want) {
          val count = handle.read(read + got, block, got, want - got)
          require(count > 0) { "A file changed while it was hashed" }
          got += count
        }
        if (v1) feedV1(block, 0, want)
        if (v2) {
          leaves += sha256Digest(if (want == BLOCK) block else block.copyOf(want))
          if (leaves.size == blocksPerPiece) {
            pieceHashes += merkleRoot(leaves, 0, pieceLevel)
            leaves.clear()
          }
        }
        read += want
        hashed += want
        if (read % pieceLength == 0L || read == entry.size) onProgress(hashed, totalBytes)
      }
    }
    if (v2 && entry.size > 0) {
      if (entry.size <= pieceLength) {
        // Its tree is only as tall as its blocks need; a file of exactly one piece is that piece.
        roots[index] = pieceHashes.firstOrNull() ?: merkleRoot(leaves, 0, null)
      } else {
        if (leaves.isNotEmpty()) pieceHashes += merkleRoot(leaves, 0, pieceLevel)
        val fileRoot = merkleRoot(pieceHashes, pieceLevel, null)
        roots[index] = fileRoot
        layers[fileRoot.toByteString()] = pieceHashes.fold(Buffer()) { out, hash ->
          out.write(hash)
        }.readByteArray()
      }
    }
    // BEP 47: a hybrid's v1 view pads every file but the last to a piece boundary.
    if (hybrid && index < entries.lastIndex && inPiece > 0) {
      var gap = pieceLength - inPiece
      while (gap > 0) {
        val take = minOf(gap, BLOCK)
        feedV1(zeros, 0, take)
        gap -= take
      }
    }
  }
  if (v1 && inPiece > 0) pieces.write(sha1.digest())

  val info = linkedMapOf<String, Any>("name" to name, "piece length" to pieceLength.toLong())
  if (options.privateTorrent) info["private"] = 1L
  options.source?.takeIf { it.isNotEmpty() }?.let { info["source"] = it }
  if (v2) {
    info["meta version"] = 2L
    info["file tree"] = fileTree(entries, roots, name, single)
  }
  if (v1) {
    info["pieces"] = pieces.readByteArray()
    if (single) {
      info["length"] = entries.single().size
    } else {
      info["files"] = v1Files(entries, pieceLength, padded = hybrid)
    }
  }
  val rawInfo = Bencode.encode(info, MAX_CREATED_BYTES)
  val document = linkedMapOf<String, Any>("info" to info)
  trackers.firstOrNull()?.firstOrNull()?.let { document["announce"] = it }
  if (trackers.sumOf { it.size } > 1) document["announce-list"] = trackers
  if (options.webSeeds.isNotEmpty()) document["url-list"] = options.webSeeds.distinct()
  options.comment?.takeIf { it.isNotEmpty() }?.let { document["comment"] = it }
  options.createdBy?.takeIf { it.isNotEmpty() }?.let { document["created by"] = it }
  options.creationDate?.let { document["creation date"] = it }
  if (v2) document["piece layers"] = layers
  val metainfo = try {
    Bencode.encode(document, MAX_CREATED_BYTES)
  } catch (e: IllegalArgumentException) {
    throw IllegalArgumentException(
      "The content needs more metadata than a 4 MiB torrent file holds; use larger pieces or " +
        "fewer files",
      e,
    )
  }
  val identity = TorrentIdentity(
    v1 = if (v1) InfoHash.fromBytes(sha1Digest(rawInfo)) else null,
    v2 = if (v2) V2InfoHash.fromBytes(sha256Digest(rawInfo)) else null,
  )
  return CreatedTorrent(
    metainfo = metainfo,
    name = name,
    infoHash = identity.v1?.hex,
    infoHashV2 = identity.v2?.hex,
    magnet = if (options.privateTorrent) {
      null
    } else {
      MagnetUri(identity, name, trackers.flatten(), webSeeds = options.webSeeds.distinct()).toUri()
    },
    totalBytes = totalBytes,
    pieceLength = pieceLength,
    fileCount = entries.size,
  )
}

/** The regular files under [root], in the order a v2 file tree lists them. */
private fun listEntries(fileSystem: FileSystem, root: Path): List<CreateEntry> {
  val found = ArrayList<CreateEntry>()
  fun walk(directory: Path, components: List<String>, depth: Int) {
    require(depth < 32) { "The folder is nested too deep for a torrent" }
    for (child in fileSystem.list(directory)) {
      val metadata = fileSystem.metadataOrNull(child) ?: continue
      // Never followed: a link could lead out of the folder, or back into it.
      if (metadata.symlinkTarget != null) continue
      val path = components + child.name
      if (metadata.isDirectory) {
        walk(child, path, depth + 1)
      } else if (metadata.isRegularFile) {
        validateTorrentComponent(child.name)
        found += CreateEntry(path, child, metadata.size ?: 0)
        require(found.size <= MAX_CREATED_FILES) {
          "A torrent holds at most $MAX_CREATED_FILES files"
        }
      }
    }
  }
  walk(root, emptyList(), 0)
  // Component by component in byte order, as bencoded dictionaries sort their keys.
  return found.sortedWith { a, b ->
    val left = a.components
    val right = b.components
    var order = 0
    var index = 0
    while (order == 0 && index < left.size && index < right.size) {
      order = left[index].encodeUtf8().compareTo(right[index].encodeUtf8())
      index++
    }
    if (order != 0) order else left.size - right.size
  }
}

/** [requested] when valid, else the power of two that gives about [TARGET_PIECES] pieces. */
private fun pieceLengthFor(requested: Int, totalBytes: Long): Int {
  if (requested != 0) {
    require(requested.toLong() in BLOCK..MAX_PIECE_LENGTH && requested.countOneBits() == 1) {
      "The piece length must be a power of two from 16 KiB to 16 MiB"
    }
    return requested
  }
  var length = BLOCK.toLong()
  while (length < MAX_PIECE_LENGTH && totalBytes / length > TARGET_PIECES) length *= 2
  return length.toInt()
}

/**
 * The root over [nodes], which sit [level] levels above the 16 KiB leaves: rows of odd size are
 * completed with the hash of an all-padding subtree. With [toLevel], the tree is that tall even
 * when fewer nodes would need less, as a piece's subtree always spans a whole piece.
 */
private fun merkleRoot(nodes: List<ByteArray>, level: Int, toLevel: Int?): ByteArray {
  require(nodes.isNotEmpty())
  var row = nodes
  var at = level
  while (row.size > 1 || (toLevel != null && at < toLevel)) {
    val next = ArrayList<ByteArray>((row.size + 1) / 2)
    var index = 0
    while (index < row.size) {
      val right = if (index + 1 < row.size) row[index + 1] else merkleZeroHash(at)
      next += newSha256().update(row[index]).update(right).digest()
      index += 2
    }
    row = next
    at++
  }
  return row[0]
}

/** The v2 `file tree`: a single file under [name], else the folder's entries. */
private fun fileTree(
  entries: List<CreateEntry>,
  roots: Array<ByteArray?>,
  name: String,
  single: Boolean,
): Map<String, Any> {
  val tree = linkedMapOf<String, Any>()
  for ((index, entry) in entries.withIndex()) {
    val leaf = linkedMapOf<String, Any>("length" to entry.size)
    roots[index]?.let { leaf["pieces root"] = it }
    val components = if (single) listOf(name) else entry.components
    var node = tree
    for (component in components) {
      @Suppress("UNCHECKED_CAST")
      node = node.getOrPut(component) { linkedMapOf<String, Any>() } as LinkedHashMap<String, Any>
    }
    require("" !in node && node.isEmpty()) { "A file and a folder share a name" }
    node[""] = leaf
  }
  return tree
}

/** The v1 `files` list, with BEP 47 padding after every file but the last when [padded]. */
private fun v1Files(
  entries: List<CreateEntry>,
  pieceLength: Int,
  padded: Boolean,
): List<Map<String, Any>> = buildList {
  var offset = 0L
  for ((index, entry) in entries.withIndex()) {
    add(mapOf("length" to entry.size, "path" to entry.components))
    offset += entry.size
    val gap = (pieceLength - offset % pieceLength) % pieceLength
    if (padded && index < entries.lastIndex && gap > 0) {
      add(mapOf("attr" to "p", "length" to gap, "path" to listOf(".pad", gap.toString())))
      offset += gap
    }
  }
}
