package com.linroid.ketch.torrent.mse

import com.linroid.ketch.torrent.crypto.Sha1
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MseHandshakeTest {
  private val skey = ByteArray(20) { it.toByte() }

  /** One direction of an in-memory socket, recording every byte written. */
  private class Pipe {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = ByteArray(0)
    var written = ByteArray(0)

    suspend fun read(size: Int): ByteArray {
      while (pending.size < size) {
        pending += chunks.receiveCatching().getOrNull() ?: throw IOException("Closed")
      }
      return pending.copyOf(size).also { pending = pending.copyOfRange(size, pending.size) }
    }

    fun write(bytes: ByteArray) {
      written += bytes
      if (chunks.trySend(bytes.copyOf()).isFailure) throw IOException("Closed")
    }

    fun close() { chunks.close() }
  }

  private class End(private val input: Pipe, private val output: Pipe) : TorrentConnection {
    override val remote = PeerEndpoint("192.0.2.1", 6881)
    override suspend fun readExactly(size: Int): ByteArray = input.read(size)
    override suspend fun write(bytes: ByteArray) = output.write(bytes)
    override fun close() {
      input.close()
      output.close()
    }
  }

  private class Link {
    val toB = Pipe()
    val toA = Pipe()
    val a = End(toA, toB)
    val b = End(toB, toA)
  }

  private suspend fun negotiate(
    link: Link,
    provide: Int,
    allowed: Int,
    skeys: List<ByteArray> = listOf(ByteArray(20), skey),
  ): Pair<TorrentConnection, TorrentConnection> = coroutineScope {
    val responder = async {
      val stream = try {
        MseHandshake.respond(link.b, link.b.readExactly(20), skeys, allowed)
      } catch (e: Exception) {
        // A responder that gives up hangs up, as a peer would.
        link.b.close()
        throw e
      }
      assertContentEquals(skey, stream.second)
      stream.first
    }
    val initiator = try {
      MseHandshake.initiate(link.a, skey, provide)
    } catch (e: Exception) {
      responder.await()
      throw e
    }
    initiator to responder.await()
  }

  @Test
  fun rc4_bothDirections_hideThePayload() = runTest {
    val link = Link()
    val (a, b) = negotiate(link, provide = 3, allowed = 3)
    assertTrue(a.encrypted && b.encrypted)
    val hello = "BitTorrent protocol, both ways".encodeToByteArray()
    a.write(hello)
    assertContentEquals(hello, b.readExactly(hello.size))
    b.write(hello.reversedArray())
    assertContentEquals(hello.reversedArray(), a.readExactly(hello.size))
    assertFalse(link.toB.written.toByteString().indexOf(hello.toByteString()) >= 0)
    assertFalse(link.toA.written.toByteString().indexOf(hello.reversedArray().toByteString()) >= 0)
  }

  @Test
  fun responderAllowingPlaintextOnly_leavesTheStreamClear() = runTest {
    val link = Link()
    val (a, b) = negotiate(link, provide = 3, allowed = MseHandshake.CRYPTO_PLAINTEXT)
    assertFalse(a.encrypted || b.encrypted)
    val hello = "clear".encodeToByteArray()
    a.write(hello)
    assertContentEquals(hello, b.readExactly(hello.size))
  }

  @Test
  fun rc4Required_againstPlaintextOnly_fails() = runTest {
    assertFailsWith<MseHandshakeException> {
      negotiate(Link(), provide = MseHandshake.CRYPTO_RC4,
        allowed = MseHandshake.CRYPTO_PLAINTEXT)
    }
  }

  @Test
  fun unknownTorrent_isRefused() = runTest {
    val error = assertFailsWith<MseHandshakeException> {
      negotiate(Link(), provide = 3, allowed = 3, skeys = listOf(ByteArray(20)))
    }
    assertIs<MseHandshakeException>(error)
  }

  @Test
  fun responder_servesTheInitialPayloadFirst() = runTest {
    // An initiator that sends its BitTorrent handshake as the initial payload, as libtorrent does.
    val link = Link()
    coroutineScope {
      val responder = async {
        MseHandshake.respond(link.b, link.b.readExactly(20), listOf(skey), 3).first
      }
      val keys = MseKeyPair()
      link.a.write(keys.publicKey)
      val secret = keys.agree(link.a.readExactly(96))
      fun hash(label: String, vararg parts: ByteArray) = Sha1().update(label.encodeToByteArray())
        .also { digest -> parts.forEach { digest.update(it) } }.digest()
      val encrypt = Rc4(hash("keyA", secret, skey), 1024)
      val req2 = hash("req2", skey)
      val req3 = hash("req3", secret)
      val ia = "initial payload".encodeToByteArray()
      val offer = okio.Buffer().write(ByteArray(8)).writeInt(2).writeShort(0)
        .writeShort(ia.size).write(ia).readByteArray()
      link.a.write(hash("req1", secret) + ByteArray(20) { (req2[it].toInt() xor
        req3[it].toInt()).toByte() } + encrypt.process(offer))
      link.a.write(encrypt.process("then the stream".encodeToByteArray()))
      val stream = responder.await()
      assertEquals("initial payloadthen the stream",
        stream.readExactly(30).decodeToString())
    }
  }

  @Test
  fun rc4_knownVector() {
    val out = Rc4("Key".encodeToByteArray()).process("Plaintext".encodeToByteArray())
    assertEquals("bbf316e8d940af0ad3", out.toByteString().hex())
  }

  @Test
  fun keyPair_agreesOnOneSecret() {
    val a = MseKeyPair()
    val b = MseKeyPair()
    assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    assertFailsWith<IllegalArgumentException> { a.agree(ByteArray(96).also { it[95] = 1 }) }
  }
}
