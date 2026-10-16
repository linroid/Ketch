package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.TorrentDispatchers
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentIdentity
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.metainfo.validateDiskComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use

/** Bounded authenticated documents, addressed by full v2 identity in a caller-private catalog. */
internal class TorrentContentCatalog(
  private val directory: Path,
  private val fileSystem: FileSystem = torrentFileSystem,
  private val slots: Semaphore = Semaphore(1),
  private val publish: (Path, Path) -> Boolean = { source, target ->
    torrentPublishCatalogObject(source, target, fileSystem)
  },
) {
  private val mutex = Mutex()
  private var rootIdentity: String? = null

  init {
    require(directory.isAbsolute && directory.parent != null)
    validateDiskComponent(directory.name)
  }

  suspend fun get(identity: TorrentIdentity): TorrentV2Document? = mutex.withLock {
    requireNotNull(identity.v2) { "V2 catalog requires a full v2 identity" }
    operation {
      val root = root(create = false) ?: return@operation null
      read(root / "${identity.v2.hex}.torrent", identity)
    }
  }

  /** Flushes a complete temporary object before publishing its content-addressed name. */
  suspend fun put(document: TorrentV2Document): TorrentIdentity = mutex.withLock {
    operation {
      val identity = document.identity
      val root = checkNotNull(root(create = true))
      val target = root / "${document.info.hash.hex}.torrent"
      if (read(target, identity) != null) return@operation identity
      val bytes = encode(document)
      // Validate the exact serialized object too; only authenticated content crosses this boundary.
      decode(bytes, identity)
      val temp = root / (".${document.info.hash.hex}-" +
        InfoHash.fromBytes(torrentRandomBytes(20)).hex + ".tmp")
      var tempIdentity: String? = null
      try {
        fileSystem.openReadWrite(temp, mustCreate = true).use { handle ->
          tempIdentity = requireNotNull(torrentFileIdentity(temp))
          handle.write(0, bytes, 0, bytes.size)
          handle.flush()
        }
        checkNotNull(root(create = false))
        // Publication cannot replace a competing entry, even if it appeared after our read.
        publish(temp, target)
        requireNotNull(read(target, identity)) { "Published catalog object disappeared" }
        identity
      } finally {
        if (tempIdentity != null && torrentFileIdentity(temp) == tempIdentity) {
          fileSystem.delete(temp, mustExist = false)
        }
      }
    }
  }

  private suspend fun <T> operation(block: () -> T): T =
    slots.withPermit { withContext(TorrentDispatchers.disk) { block() } }

  private fun root(create: Boolean): Path? {
    val path = fileSystem.canonicalize(checkNotNull(directory.parent)) / directory.name
    var metadata = fileSystem.metadataOrNull(path)
    if (metadata == null) {
      if (!create) return null
      require(rootIdentity == null) { "Catalog directory disappeared" }
      try {
        fileSystem.createDirectory(path, mustCreate = true)
      } catch (error: IOException) {
        val concurrent = fileSystem.metadataOrNull(path)
        if (concurrent?.isDirectory != true || concurrent.symlinkTarget != null) throw error
      }
      metadata = fileSystem.metadata(path)
    }
    require(metadata.isDirectory && metadata.symlinkTarget == null) { "Unsafe catalog directory" }
    val identity = requireNotNull(torrentFileIdentity(path)) { "Catalog identity is unprovable" }
    require(rootIdentity == null || rootIdentity == identity) { "Catalog directory changed" }
    rootIdentity = identity
    return path
  }

  private fun read(path: Path, identity: TorrentIdentity): TorrentV2Document? {
    val metadata = fileSystem.metadataOrNull(path) ?: return null
    require(metadata.isRegularFile && metadata.symlinkTarget == null) { "Unsafe catalog object" }
    // A publication a crash interrupted, which the next one takes over.
    if (metadata.size == 0L) return null
    val size = requireNotNull(metadata.size)
    require(size in 1..MAX_BYTES.toLong()) { "Catalog object exceeds byte limit" }
    val bytes = fileSystem.read(path) {
      val result = readByteArray(size)
      require(exhausted()) { "Catalog object grew during read" }
      result
    }
    return decode(bytes, identity)
  }

  private fun encode(document: TorrentV2Document): ByteArray {
    val prefix = "d4:info".encodeToByteArray()
    val middle = "12:piece layers".encodeToByteArray()
    val info = document.info.rawInfo
    val available = MAX_BYTES - prefix.size - middle.size - info.size - 1
    require(available > 0)
    val layers = Bencode.encode(document.pieceLayers, maxBytes = available)
    return prefix + info.toByteArray() + middle + layers + byteArrayOf('e'.code.toByte())
  }

  private fun decode(bytes: ByteArray, identity: TorrentIdentity): TorrentV2Document =
    TorrentV2Document.parse(bytes, maxDocumentBytes = MAX_BYTES, maxInfoBytes = MAX_BYTES,
      maxNodes = 1_000_000, maxFiles = 100_000, maxLayerBytes = MAX_BYTES.toLong(),
      expectedIdentity = identity)

  companion object {
    // The streaming/spilled catalog path for full production layer limits remains separate work.
    const val MAX_BYTES = 32 * 1024 * 1024
  }
}

/**
 * Atomically adds a name for the complete file [source]; false means [target] already exists.
 * It links [source] where the file system has hard links. Where it has none (FAT, exFAT and some
 * network shares), it claims [target] with an exclusive create, then moves [source] over it, so
 * it never replaces a file either way. A crash between the two leaves an empty file at [target]:
 * readers take it as not published, and the next publication moves over it.
 */
internal fun torrentPublishCatalogObject(
  source: Path,
  target: Path,
  fileSystem: FileSystem = torrentFileSystem,
  link: (source: Path, target: Path) -> Boolean = ::torrentLinkFile,
): Boolean {
  val refused = try {
    if (link(source, target)) return true
    null
  } catch (e: IOException) {
    e
  }
  if (refused == null) {
    if (!isUnpublished(fileSystem, target)) return false
  } else {
    try {
      fileSystem.openReadWrite(target, mustCreate = true).close()
    } catch (e: IOException) {
      if (fileSystem.metadataOrNull(target) == null) throw e.apply { addSuppressed(refused) }
      if (!isUnpublished(fileSystem, target)) return false
    }
  }
  fileSystem.atomicMove(source, target)
  return true
}

/** Whether [path] is an empty regular file: a name a publication claimed and never filled. */
internal fun isUnpublished(fileSystem: FileSystem, path: Path): Boolean {
  val metadata = fileSystem.metadataOrNull(path) ?: return false
  return metadata.isRegularFile && metadata.symlinkTarget == null && metadata.size == 0L
}

/**
 * Links [target] to the complete file [source]; false when [target] exists. Throws when the
 * file system refuses hard links or for any other failure.
 */
internal expect fun torrentLinkFile(source: Path, target: Path): Boolean
