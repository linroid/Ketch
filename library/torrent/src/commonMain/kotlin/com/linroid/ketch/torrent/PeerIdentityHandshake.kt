package com.linroid.ketch.torrent

import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Full identities remain attached to a route; 20-byte wire tags never become content keys. */
internal class PeerIdentityHandshake(
  private val identity: TorrentIdentity,
  private val extensions: Boolean = false,
  private val dht: Boolean = false,
) {
  enum class Mode { V1, V2 }
  data class Result(
    val identity: TorrentIdentity,
    val mode: Mode,
    val peerId: ByteString,
    val extensions: Boolean,
    val dht: Boolean,
  )

  private data class Envelope(
    val tag: ByteString,
    val peerId: ByteString,
    val extensions: Boolean,
    val dht: Boolean,
    val upgrade: Boolean,
  )
  private val v1 = identity.v1?.toBytes()?.toByteString()
  private val v2 = identity.v2?.wireBytes()?.toByteString()

  suspend fun initiate(
    connection: TorrentConnection,
    mode: Mode,
    peerId: ByteString,
    budget: TorrentBufferBudget,
    allowUpgrade: Boolean = false,
    expectedPeerId: ByteString? = null,
  ): Result = admitted(connection, budget) {
    require(peerId.size == 20 && (expectedPeerId == null || expectedPeerId.size == 20))
    val offer = allowUpgrade && mode == Mode.V1 && v1 != null && v2 != null
    require(!offer || v1 != v2) { "Ambiguous hybrid upgrade tag" }
    connection.write(encode(mode, peerId, offer))
    val remote = decode(connection.readExactly(68))
    val negotiated = when {
      remote.tag == tag(mode) -> mode
      offer && remote.tag == v2 -> Mode.V2
      else -> throw IllegalArgumentException("Peer handshake changed the requested swarm")
    }
    result(remote, negotiated, peerId, expectedPeerId)
  }

  suspend fun respond(
    connection: TorrentConnection,
    peerId: ByteString,
    budget: TorrentBufferBudget,
    allowUpgrade: Boolean = false,
  ): Result = admitted(connection, budget) {
    require(peerId.size == 20)
    val remote = decode(connection.readExactly(68))
    val matchesV1 = remote.tag == v1
    val matchesV2 = remote.tag == v2
    require(matchesV1 != matchesV2) { "Unknown or ambiguous incoming swarm tag" }
    val mode = if (matchesV2 || allowUpgrade && remote.upgrade && v2 != null) Mode.V2 else Mode.V1
    val result = result(remote, mode, peerId, null)
    connection.write(encode(mode, peerId, upgrade = false))
    result
  }

  private fun result(
    remote: Envelope,
    mode: Mode,
    localPeerId: ByteString,
    expectedPeerId: ByteString?,
  ): Result {
    require(remote.peerId != localPeerId) { "Self peer connection" }
    require(expectedPeerId == null || remote.peerId == expectedPeerId) { "Unexpected peer ID" }
    return Result(identity, mode, remote.peerId, remote.extensions, remote.dht)
  }

  private fun tag(mode: Mode): ByteString = requireNotNull(if (mode == Mode.V1) v1 else v2) {
    "Requested protocol is absent from the full torrent identity"
  }

  private fun encode(mode: Mode, peerId: ByteString, upgrade: Boolean): ByteArray {
    val reserved = ByteArray(8)
    if (extensions) reserved[5] = 0x10
    if (dht) reserved[7] = 1
    if (upgrade) reserved[7] = (reserved[7].toInt() or 0x10).toByte()
    return Buffer().writeByte(19).write(PROTOCOL).write(reserved).write(tag(mode))
      .write(peerId).readByteArray()
  }

  private fun decode(bytes: ByteArray): Envelope {
    require(bytes.size == 68 && bytes[0] == 19.toByte() &&
      bytes.copyOfRange(1, 20).contentEquals(PROTOCOL)) { "Invalid peer handshake" }
    return Envelope(bytes.copyOfRange(28, 48).toByteString(),
      bytes.copyOfRange(48, 68).toByteString(),
      bytes[25].toInt() and 0x10 != 0, bytes[27].toInt() and 1 != 0,
      bytes[27].toInt() and 0x10 != 0)
  }

  private suspend fun admitted(
    connection: TorrentConnection,
    budget: TorrentBufferBudget,
    block: suspend () -> Result,
  ): Result {
    var lease: TorrentBufferBudget.Lease? = null
    try {
      lease = checkNotNull(budget.reserve(4096)) { "Handshake budget exhausted" }
      return withTimeout(10_000) { block() }
    } catch (error: Throwable) {
      connection.close()
      throw error
    } finally {
      lease?.close()
    }
  }

  companion object {
    private val PROTOCOL = "BitTorrent protocol".encodeToByteArray()
  }
}
