package com.linroid.ketch.torrent.mse

import com.linroid.ketch.torrent.crypto.newSha1
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.net.PrefixedConnection
import com.linroid.ketch.torrent.net.TorrentConnection
import okio.Buffer
import okio.IOException

/**
 * The peer did not complete an MSE/PE handshake: it hung up, stalled or sent something else.
 *
 * @property leadingZeroSecret the exchange agreed on a shared secret whose first byte is zero,
 *   which older libtorrent hashes differently (see [MseHandshake]): the same peer may well accept
 *   a handshake with new keys
 */
internal class MseHandshakeException(
  message: String,
  cause: Throwable? = null,
  val leadingZeroSecret: Boolean = false,
) : IOException(message, cause)

/**
 * Message Stream Encryption / Protocol Encryption, the obfuscation BitTorrent clients negotiate
 * before the BitTorrent handshake: a Diffie-Hellman exchange, then RC4 keyed by the shared
 * secret and the torrent's 20-byte wire tag (SKEY). Ketch sends no initial payload (IA): the
 * BitTorrent handshake follows on the negotiated stream, so every handshake above it is unchanged.
 *
 * The shared secret S is always its 96 big-endian bytes. libtorrent before 2.0.13 and 2.1.0
 * hashed the `req3` mask over S without its leading zero bytes, left-aligned in a 96-byte buffer
 * whose tail it never initialized, while its sync hash and RC4 keys use S as specified: about one
 * handshake in 256 with such a peer fails. Dialing, Ketch sends the specified mask and reports a
 * failure after such a secret as [MseHandshakeException.leadingZeroSecret], so the dial can try
 * again with new keys. Answering, it also accepts the mask with that tail zero, which matches
 * only when the peer's buffer happened to hold zeros; otherwise the peer's own retry gets a new
 * secret.
 */
internal object MseHandshake {
  /** RC4 for the rest of the stream; plaintext leaves it in the clear after the handshake. */
  const val CRYPTO_PLAINTEXT = 0x01
  const val CRYPTO_RC4 = 0x02

  private const val MAX_PAD = 512
  private val VC = ByteArray(8)

  /** The opening of a plaintext BitTorrent handshake: an incoming peer that sends it skips MSE. */
  val plaintextHeader: ByteArray = byteArrayOf(19) + "BitTorrent protocol".encodeToByteArray()

  /**
   * Negotiates as the dialing side for the torrent with wire tag [skey], offering [provide]
   * (a mask of [CRYPTO_PLAINTEXT] and [CRYPTO_RC4]). Returns the stream to speak BitTorrent on.
   */
  suspend fun initiate(
    connection: TorrentConnection,
    skey: ByteArray,
    provide: Int,
    keys: MseKeyPair = MseKeyPair(),
  ): TorrentConnection {
    var secret: ByteArray? = null
    try {
      return failing { negotiate(connection, skey, provide, keys) { secret = it } }
    } catch (e: MseHandshakeException) {
      if (secret?.get(0) != 0.toByte() || e.leadingZeroSecret) throw e
      throw MseHandshakeException(e.message ?: "MSE handshake failed", e, leadingZeroSecret = true)
    }
  }

  private suspend fun negotiate(
    connection: TorrentConnection,
    skey: ByteArray,
    provide: Int,
    keys: MseKeyPair,
    onSecret: (ByteArray) -> Unit,
  ): TorrentConnection {
    require(skey.size == 20 && provide in 1..3)
    connection.write(keys.publicKey + torrentRandomBytes(randomPad()))
    val secret = keys.agree(connection.readExactly(MseKeyPair.KEY_BYTES))
    onSecret(secret)
    val encrypt = Rc4(hash("keyA", secret, skey), DISCARD)
    val decrypt = Rc4(hash("keyB", secret, skey), DISCARD)
    val req2 = hash("req2", skey)
    val req3 = hash("req3", secret)
    val offer = Buffer().write(VC).writeInt(provide).writeShort(0).writeShort(0).readByteArray()
    connection.write(hash("req1", secret) + ByteArray(20) { (req2[it].toInt() xor
      req3[it].toInt()).toByte() } + encrypt.process(offer))
    // The peer's pad hides where its answer starts: find the encrypted verification constant.
    val marker = decrypt.process(VC.copyOf())
    scan(connection, marker, MAX_PAD + marker.size)
    val answer = decrypt.process(connection.readExactly(6))
    val selected = Buffer().write(answer).readInt()
    val padLength = ((answer[4].toInt() and 255) shl 8) or (answer[5].toInt() and 255)
    if (selected != CRYPTO_RC4 && selected != CRYPTO_PLAINTEXT || selected and provide == 0) {
      throw MseHandshakeException("Peer selected an unoffered MSE method $selected")
    }
    if (padLength > MAX_PAD) throw MseHandshakeException("MSE pad too long")
    if (padLength > 0) decrypt.process(connection.readExactly(padLength))
    return if (selected == CRYPTO_RC4) Rc4Connection(connection, encrypt, decrypt) else connection
  }

  /**
   * Negotiates as the answering side: [opening] is what the peer sent first, 20 bytes or more of
   * its public key. Accepts the torrent whose wire tag is among [skeys] and selects RC4 when the
   * peer offers it and [allowed] does, else plaintext. Returns the stream, the initial payload
   * already in front, and the wire tag the peer asked for.
   */
  suspend fun respond(
    connection: TorrentConnection,
    opening: ByteArray,
    skeys: Collection<ByteArray>,
    allowed: Int,
    keys: MseKeyPair = MseKeyPair(),
  ): Pair<TorrentConnection, ByteArray> = failing {
    require(opening.size <= MseKeyPair.KEY_BYTES && allowed in 1..3)
    val theirs = opening + connection.readExactly(MseKeyPair.KEY_BYTES - opening.size)
    val secret = keys.agree(theirs)
    connection.write(keys.publicKey + torrentRandomBytes(randomPad()))
    scan(connection, hash("req1", secret), MAX_PAD + 20)
    val mixed = connection.readExactly(20)
    // An older libtorrent masks with S left-aligned when S starts with zero bytes; its tail is
    // whatever its buffer held, so the zero-filled guess is best effort.
    val masks = listOfNotNull(hash("req3", secret), legacyMask(secret))
    val skey = skeys.firstOrNull { candidate ->
      val req2 = hash("req2", candidate)
      masks.any { req3 ->
        (0 until 20).all { (req2[it].toInt() xor req3[it].toInt()).toByte() == mixed[it] }
      }
    } ?: throw MseHandshakeException("Peer asked for a torrent we do not serve")
    val decrypt = Rc4(hash("keyA", secret, skey), DISCARD)
    val encrypt = Rc4(hash("keyB", secret, skey), DISCARD)
    val head = decrypt.process(connection.readExactly(14))
    if (!head.copyOfRange(0, 8).contentEquals(VC)) {
      throw MseHandshakeException("MSE verification constant mismatch")
    }
    val fields = Buffer().write(head, 8, 6)
    val provide = fields.readInt()
    val padLength = fields.readShort().toInt() and 0xffff
    if (padLength > MAX_PAD) throw MseHandshakeException("MSE pad too long")
    if (padLength > 0) decrypt.process(connection.readExactly(padLength))
    val iaLength = decrypt.process(connection.readExactly(2))
      .let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
    if (iaLength > 68 * 4) throw MseHandshakeException("MSE initial payload too long")
    val initial = if (iaLength > 0) decrypt.process(connection.readExactly(iaLength))
      else ByteArray(0)
    val select = when {
      provide and allowed and CRYPTO_RC4 != 0 -> CRYPTO_RC4
      provide and allowed and CRYPTO_PLAINTEXT != 0 -> CRYPTO_PLAINTEXT
      else -> throw MseHandshakeException("No MSE method in common")
    }
    connection.write(encrypt.process(Buffer().write(VC).writeInt(select).writeShort(0)
      .readByteArray()))
    val stream = if (select == CRYPTO_RC4) Rc4Connection(connection, encrypt, decrypt)
      else connection
    PrefixedConnection(stream, initial) to skey
  }

  /** Reads until [pattern] has been read, within [limit] bytes. */
  private suspend fun scan(connection: TorrentConnection, pattern: ByteArray, limit: Int) {
    val window = ByteArray(pattern.size)
    var read = 0
    while (read < limit) {
      val byte = connection.readExactly(1)[0]
      window.copyInto(window, 0, 1)
      window[window.size - 1] = byte
      read++
      if (read >= pattern.size && window.contentEquals(pattern)) return
    }
    throw MseHandshakeException("MSE synchronization not found")
  }

  /**
   * The `req3` mask libtorrent before 2.0.13 and 2.1.0 computes for [secret] when its buffer's
   * tail held zeros: over its significant bytes, left-aligned and zero-filled to 96; `null` when
   * [secret] has no leading zero byte.
   */
  internal fun legacyMask(secret: ByteArray): ByteArray? {
    if (secret[0] != 0.toByte()) return null
    val significant = secret.dropWhile { it == 0.toByte() }.toByteArray()
    return hash("req3", significant.copyOf(secret.size))
  }

  internal fun hash(label: String, vararg parts: ByteArray): ByteArray {
    val digest = newSha1().update(label.encodeToByteArray())
    parts.forEach { digest.update(it) }
    return digest.digest()
  }

  private fun randomPad(): Int = (torrentRandomBytes(2).let {
    ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)
  }) % (MAX_PAD + 1)

  /** Reports a failed exchange as [MseHandshakeException], keeping cancellation as it is. */
  private inline fun <T> failing(block: () -> T): T = try {
    block()
  } catch (e: MseHandshakeException) {
    throw e
  } catch (e: IllegalArgumentException) {
    throw MseHandshakeException(e.message ?: "Invalid MSE handshake", e)
  } catch (e: IOException) {
    throw MseHandshakeException("Peer closed during the MSE handshake", e)
  }

  private const val DISCARD = 1024
}

/** A stream whose bytes are RC4 in both directions; the two keystreams are separate. */
internal class Rc4Connection(
  private val delegate: TorrentConnection,
  private val encrypt: Rc4,
  private val decrypt: Rc4,
) : TorrentConnection by delegate {
  override val encrypted: Boolean get() = true

  override suspend fun readExactly(size: Int): ByteArray =
    decrypt.process(delegate.readExactly(size))

  override suspend fun write(bytes: ByteArray) {
    delegate.write(encrypt.process(bytes.copyOf()))
  }
}
