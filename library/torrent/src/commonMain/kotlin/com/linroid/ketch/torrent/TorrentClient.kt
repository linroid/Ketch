package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.torrent.crypto.torrentRandomBytes

/**
 * How Ketch names itself to peers and trackers, which private trackers check against the clients
 * they allow: the BEP 20 peer ID, its BEP 10 `v` name and the `User-Agent` of tracker requests,
 * all from [KetchApi.VERSION].
 */
internal object TorrentClient {
  /**
   * The client code of our peer IDs. `KT`, the obvious one, is KTorrent's in the client tables
   * of Transmission, libtorrent and BiglyBT.
   */
  const val CODE = "KE"

  /** The BEP 10 `v` of our extension handshakes, such as `Ketch 0.9.1`. */
  val name: String = "Ketch ${KetchApi.VERSION}"

  /** The `User-Agent` of tracker requests, the one Ketch's HTTP engine sends by default. */
  val userAgent: String = "Ketch/${KetchApi.VERSION}"

  /** The first eight characters of every peer ID, such as `-KE0910-`. */
  val peerIdPrefix: String = peerIdPrefix(KetchApi.VERSION)

  /** A new peer ID: [peerIdPrefix] and twelve random letters and digits. */
  fun peerId(): ByteArray = peerId(peerIdPrefix)

  /**
   * The Azureus-style prefix (BEP 20) of [version]: `-KE`, one character each for the major,
   * minor and patch numbers (`0`-`9`, then `A`-`Z` and `a`-`z`, at most `z`), `0` for a release
   * or `Z` for anything else, and `-`. A version that does not start `major.minor` is `000Z`.
   */
  fun peerIdPrefix(version: String): String {
    val match = RELEASE.find(version)
    val numbers = match?.groupValues?.subList(1, 4)?.map { number ->
      if (number.isEmpty()) 0 else number.toIntOrNull() ?: Int.MAX_VALUE
    } ?: listOf(0, 0, 0)
    val stage = if (match != null && match.groupValues[4].isEmpty()) '0' else 'Z'
    return buildString {
      append('-').append(CODE)
      for (number in numbers) append(ALPHANUMERIC[number.coerceAtMost(ALPHANUMERIC.lastIndex)])
      append(stage).append('-')
    }
  }

  internal fun peerId(prefix: String): ByteArray {
    require(prefix.length == PREFIX_LENGTH)
    val id = ByteArray(PEER_ID_LENGTH)
    prefix.encodeToByteArray().copyInto(id)
    var filled = PREFIX_LENGTH
    // Bytes from 248 up are skipped, so every character is as likely as any other.
    while (filled < PEER_ID_LENGTH) {
      for (byte in torrentRandomBytes(PEER_ID_LENGTH)) {
        val value = byte.toInt() and 255
        if (value >= UNBIASED_LIMIT) continue
        id[filled++] = ALPHANUMERIC[value % ALPHANUMERIC.length].code.toByte()
        if (filled == PEER_ID_LENGTH) break
      }
    }
    return id
  }

  private const val PEER_ID_LENGTH = 20
  private const val PREFIX_LENGTH = 8
  private const val ALPHANUMERIC =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private const val UNBIASED_LIMIT = 256 / ALPHANUMERIC.length * ALPHANUMERIC.length
}

// Outside the object, which reads it while it initializes.
private val RELEASE = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(.*)$""")
