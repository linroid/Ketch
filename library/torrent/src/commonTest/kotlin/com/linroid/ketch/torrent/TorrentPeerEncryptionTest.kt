package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentPeerEncryptionTest {
  private val tag = ByteArray(20) { 7 }
  private val peer = PeerEndpoint("192.0.2.9", 6881)

  private class Pipe {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = ByteArray(0)

    suspend fun read(size: Int): ByteArray {
      while (pending.size < size) {
        pending += chunks.receiveCatching().getOrNull() ?: throw IOException("Closed")
      }
      return pending.copyOf(size).also { pending = pending.copyOfRange(size, pending.size) }
    }

    fun write(bytes: ByteArray) {
      if (chunks.trySend(bytes.copyOf()).isFailure) throw IOException("Closed")
    }

    fun close() { chunks.close() }
  }

  private class End(
    override val remote: PeerEndpoint,
    private val input: Pipe,
    private val output: Pipe,
  ) : TorrentConnection {
    override suspend fun readExactly(size: Int): ByteArray = input.read(size)
    override suspend fun write(bytes: ByteArray) = output.write(bytes)
    override fun close() {
      input.close()
      output.close()
    }
  }

  /** A peer without encryption: it hangs up on anything but a plaintext handshake. */
  private inner class PlaintextPeers(private val scope: CoroutineScope) : TorrentNetwork {
    var connects = 0

    override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
      connects++
      val toPeer = Pipe()
      val toUs = Pipe()
      val theirs = End(remote, toPeer, toUs)
      scope.launch {
        val opening = runCatching { theirs.readExactly(20) }.getOrNull()
        if (opening == null || !opening.contentEquals(MseHandshake.plaintextHeader)) theirs.close()
      }
      return End(remote, toUs, toPeer)
    }

    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
    override fun close() = Unit
  }

  @Test
  fun preferred_peerWithoutEncryption_isDialedAgainInPlaintextAndRemembered() = runTest {
    val peers = PlaintextPeers(backgroundScope)
    val network = TorrentPeerEncryption(peers, TorrentEncryption.PREFERRED)

    val first = network.connectPeer(peer, tag)
    assertFalse(first.encrypted)
    assertEquals(2, peers.connects)

    network.connectPeer(peer, tag)
    assertEquals(3, peers.connects, "Dialed in plaintext at once")
  }

  @Test
  fun preferred_peerSilentOnMse_getsItsPlaintextRetryPastTheConnectTimeout() = runTest {
    // The peer accepts and reads, but never answers an encrypted opening.
    val silent = object : TorrentNetwork {
      var connects = 0
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
        connects++
        return End(remote, Pipe(), Pipe())
      }
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = error("Unused")
      override fun close() = Unit
    }
    val network = TorrentPeerEncryption(silent, TorrentEncryption.PREFERRED,
      handshakeTimeoutMs = 2_000)
    // The connect timeout bounds each TCP connect, not the negotiation and its retry.
    val connection = network.connectPeer(peer, tag, connectTimeoutMs = 1_000)
    assertFalse(connection.encrypted)
    assertEquals(2, silent.connects)
  }

  @Test
  fun required_peerWithoutEncryption_fails() = runTest {
    val peers = PlaintextPeers(backgroundScope)
    val network = TorrentPeerEncryption(peers, TorrentEncryption.REQUIRED)
    assertFailsWith<MseHandshakeException> { network.connectPeer(peer, tag) }
    assertEquals(1, peers.connects)
  }

  @Test
  fun disabled_dialsPlaintext() = runTest {
    val peers = PlaintextPeers(backgroundScope)
    val network = TorrentPeerEncryption(peers, TorrentEncryption.DISABLED)
    assertFalse(network.connectPeer(peer, tag).encrypted)
    assertEquals(1, peers.connects)
  }

  @Test
  fun accept_plaintextHandshake_passesThroughUnlessRequired() = runTest {
    val header = MseHandshake.plaintextHeader + ByteArray(48) { 1 }
    for (policy in TorrentEncryption.entries) {
      val toUs = Pipe()
      val incoming = End(peer, toUs, Pipe())
      toUs.write(header)
      val network = TorrentPeerEncryption(PlaintextPeers(backgroundScope), policy)
      if (policy == TorrentEncryption.REQUIRED) {
        assertFailsWith<IOException> { network.accept(incoming, emptyList()) }
      } else {
        assertContentEquals(header, network.accept(incoming, emptyList()).readExactly(68))
      }
    }
  }

  @Test
  fun accept_encryptedOpening_negotiatesUnlessDisabled() = runTest {
    for (policy in TorrentEncryption.entries) {
      val toUs = Pipe()
      val toPeer = Pipe()
      val incoming = End(peer, toUs, toPeer)
      val dialer = End(peer, toPeer, toUs)
      val network = TorrentPeerEncryption(PlaintextPeers(backgroundScope), policy)
      val initiated = backgroundScope.launch {
        runCatching {
          MseHandshake.initiate(dialer, tag, MseHandshake.CRYPTO_RC4)
            .write("hello".encodeToByteArray())
        }
      }
      if (policy == TorrentEncryption.DISABLED) {
        assertFailsWith<IOException> { network.accept(incoming, listOf(tag.toByteString())) }
        incoming.close()
      } else {
        val stream = network.accept(incoming, listOf(tag.toByteString()))
        assertTrue(stream.encrypted)
        assertEquals("hello", stream.readExactly(5).decodeToString())
      }
      initiated.join()
    }
  }
}
