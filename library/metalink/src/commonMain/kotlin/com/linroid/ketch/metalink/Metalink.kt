package com.linroid.ketch.metalink

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.file.sanitizeFileName
import com.linroid.ketch.core.mirror.Mirror
import com.linroid.ketch.core.mirror.PieceHashes
import kotlinx.serialization.Serializable

/**
 * The files a Metalink document describes, each with the HTTP(S) mirrors that serve it, as a
 * task keeps them ([com.linroid.ketch.api.ResolvedSource.METAINFO_KEY]). Later builds must read
 * what earlier ones saved: add fields with defaults only.
 */
@Serializable
internal data class Metalink(val files: List<MetalinkFile>)

/**
 * One file of a [Metalink].
 *
 * @property name the name it is saved under, already sanitized
 * @property size its size, when the document gives it
 * @property hashes digests of the whole file, one per supported algorithm
 * @property pieces digests of its pieces, when the document gives them in a supported algorithm
 * @property urls its usable mirrors, best first
 */
@Serializable
internal data class MetalinkFile(
  val name: String,
  val size: Long? = null,
  val hashes: List<Checksum> = emptyList(),
  val pieces: MetalinkPieces? = null,
  val urls: List<MetalinkUrl> = emptyList(),
) {
  /** The strongest whole-file digest. */
  val checksum: Checksum? get() = hashes.maxByOrNull { it.algorithm.byteCount }

  fun mirrors(): List<Mirror> = urls.map { Mirror(it.url, it.priority, it.location) }

  fun pieceHashes(): PieceHashes? = pieces?.let { pieces ->
    PieceHashes(
      pieces.algorithm,
      pieces.length,
      pieces.hashes.map { Checksum(pieces.algorithm, it) },
    )
  }
}

/** Piece digests as lowercase hex, each [length] bytes of the file but the last. */
@Serializable
internal data class MetalinkPieces(
  val algorithm: ChecksumAlgorithm,
  val length: Long,
  val hashes: List<String>,
)

/** A mirror URL; lower [priority] is preferred. */
@Serializable
internal data class MetalinkUrl(
  val url: String,
  val priority: Int = 0,
  val location: String? = null,
)

/** A document [parseMetalink] cannot use. */
internal class MetalinkException(message: String) : Exception(message)

/**
 * Reads a Metalink 4 (RFC 5854, `.meta4`) or Metalink 3 (`.metalink`) document.
 *
 * Only HTTP and HTTPS mirrors are kept, without user info, at most [MAX_MIRRORS] per file; other
 * resources, such as FTP, BitTorrent `metaurl`s and P2P links, are skipped, as are files left
 * without a mirror. Hashes in algorithms Ketch lacks, such as SHA-384, are skipped too. When the
 * document came over HTTPS ([secure]) a file needs a SHA-1 or stronger digest to keep its plain
 * HTTP mirrors, which could otherwise send anything.
 *
 * @throws MetalinkException when the document is not a Metalink or lists no usable file
 */
internal fun parseMetalink(text: String, secure: Boolean): Metalink {
  val root = try {
    parseXml(text)
  } catch (e: XmlException) {
    throw MetalinkException("Not a Metalink document: ${e.message}")
  }
  if (root.name != "metalink") throw MetalinkException("Not a Metalink document")
  // Metalink 3 lists its files in <files>; Metalink 4 directly under the root.
  val legacy = root.child("files")
  val entries = legacy?.children("file") ?: root.children("file")
  if (entries.size > MAX_FILES) throw MetalinkException("A Metalink lists at most $MAX_FILES files")
  val files = entries.mapNotNull { file -> readFile(file, legacy = legacy != null, secure) }
  if (files.isEmpty()) throw MetalinkException("The Metalink lists no file with an HTTP mirror")
  return Metalink(files)
}

private fun readFile(file: XmlElement, legacy: Boolean, secure: Boolean): MetalinkFile? {
  val name = file.attributes["name"]?.let(::sanitizeFileName) ?: return null
  val size = file.child("size")?.text?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
  val verification = if (legacy) file.child("verification") else file
  val hashes = verification?.children("hash").orEmpty().mapNotNull { hash ->
    val algorithm = hash.attributes["type"]?.let(ChecksumAlgorithm::named) ?: return@mapNotNull null
    try {
      Checksum.parse(algorithm, hash.text)
    } catch (_: IllegalArgumentException) {
      null
    }
  }.distinctBy { it.algorithm }
  val pieces = verification?.children("pieces").orEmpty().firstNotNullOfOrNull { readPieces(it) }

  // Without a strong digest, nothing tells a plain HTTP mirror's bytes from an attacker's.
  val strong = (hashes.map { it.algorithm } + listOfNotNull(pieces?.algorithm))
    .any { it != ChecksumAlgorithm.MD5 }
  val resources = if (legacy) file.child("resources")?.children("url") else file.children("url")
  val urls = resources.orEmpty().mapNotNull { url ->
    // Metalink 3 also lists BitTorrent, ed2k and other resources by type.
    val kind = url.attributes["type"]?.trim()?.lowercase()
    if (legacy && kind != null && kind != "http" && kind != "https") return@mapNotNull null
    val target = mirrorUrl(url.text) ?: return@mapNotNull null
    if (secure && !strong && !target.startsWith("https://")) return@mapNotNull null
    val priority = if (legacy) {
      // Metalink 3 ranks by preference, 100 the best.
      100 - (url.attributes["preference"]?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: 0)
    } else {
      url.attributes["priority"]?.trim()?.toIntOrNull()?.coerceIn(1, 999_999) ?: 999_999
    }
    val location = url.attributes["location"]?.trim()?.lowercase()
      ?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
    MetalinkUrl(target, priority, location)
  }.distinctBy { it.url }.sortedBy { it.priority }.take(MAX_MIRRORS)
  if (urls.isEmpty()) return null
  return MetalinkFile(name, size, hashes, pieces, urls)
}

private fun readPieces(element: XmlElement): MetalinkPieces? {
  val algorithm = element.attributes["type"]?.let(ChecksumAlgorithm::named) ?: return null
  val length = element.attributes["length"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    ?: return null
  val hashes = element.children("hash")
  // Metalink 3 numbers its pieces; Metalink 4 lists them in order.
  val ordered = if (hashes.any { "piece" in it.attributes }) {
    val numbered = hashes.map { (it.attributes["piece"]?.trim()?.toIntOrNull() ?: -1) to it }
      .sortedBy { it.first }
    if (numbered.withIndex().any { (i, entry) -> entry.first != i }) return null
    numbered.map { it.second }
  } else {
    hashes
  }
  if (ordered.isEmpty()) return null
  val hex = ordered.map { hash ->
    try {
      Checksum.parse(algorithm, hash.text).value
    } catch (_: IllegalArgumentException) {
      return null
    }
  }
  return MetalinkPieces(algorithm, length, hex)
}

/** [text] as an absolute HTTP(S) URL without user info, or `null`. */
internal fun mirrorUrl(text: String): String? {
  val url = text.trim()
  if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() || it < ' ' || it == '\\' }) {
    return null
  }
  val match = HTTP_URL.matchEntire(url) ?: return null
  val authority = match.groupValues[2]
  if ('@' in authority) return null
  return match.groupValues[1].lowercase() + "://" + authority + match.groupValues[3]
}

/** The most files one Metalink may list. */
internal const val MAX_FILES = 1000

/** The most mirrors kept per file. */
internal const val MAX_MIRRORS = 64

private const val MAX_URL_LENGTH = 8192
private val HTTP_URL = Regex("(https?)://([^/?#]+)(.*)", RegexOption.IGNORE_CASE)
