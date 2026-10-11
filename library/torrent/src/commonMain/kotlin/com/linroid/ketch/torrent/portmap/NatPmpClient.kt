package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.numericHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/** The transport protocol of a port mapping; NAT-PMP numbers them 1 and 2. */
internal enum class MappedProtocol(val natPmpOpcode: Int) { UDP(1), TCP(2) }

/** A port a gateway forwards to us until [lifetimeSeconds] pass. */
internal data class PortMapping(
  val protocol: MappedProtocol,
  val internalPort: Int,
  val externalPort: Int,
  val lifetimeSeconds: Long,
)

/**
 * NAT-PMP (RFC 6886): asks a gateway's port 5351 over UDP for its external address and port
 * mappings. Requests are sent again after 250, 500 and 1000 ms, and only replies from the
 * gateway asked count.
 */
internal class NatPmpClient(
  private val network: TorrentNetwork,
  private val port: Int = NAT_PMP_PORT,
  private val retryMs: List<Long> = listOf(250, 500, 1000),
) {
  /** The first of [candidates] that answers, with its external address, or null. */
  suspend fun discover(candidates: List<ByteArray>): Pair<ByteArray, ByteArray>? = coroutineScope {
    candidates.distinctBy { it.toList() }.take(8).map { gateway ->
      async { externalAddress(gateway)?.let { gateway to it } }
    }.awaitAll().firstOrNull { it != null }
  }

  /** The gateway's external IPv4 address, or null when it does not answer. */
  suspend fun externalAddress(gateway: ByteArray): ByteArray? {
    val reply = exchange(gateway, byteArrayOf(0, 0), opcode = 0, minSize = 12) ?: return null
    return reply.copyOfRange(8, 12)
  }

  /**
   * Maps [internalPort] for [lifetimeSeconds], asking for [externalPort]; the gateway may pick
   * another. A lifetime of 0 removes the mapping. Null when the gateway refuses or is silent.
   */
  suspend fun map(
    gateway: ByteArray,
    protocol: MappedProtocol,
    internalPort: Int,
    externalPort: Int,
    lifetimeSeconds: Long,
  ): PortMapping? {
    require(internalPort in 1..65535 && externalPort in 0..65535 && lifetimeSeconds >= 0)
    val request = Buffer().writeByte(0).writeByte(protocol.natPmpOpcode).writeShort(0)
      .writeShort(internalPort).writeShort(externalPort).writeInt(lifetimeSeconds.toInt())
      .readByteArray()
    val reply = exchange(gateway, request, protocol.natPmpOpcode, minSize = 16) ?: return null
    val fields = Buffer().write(reply, 8, 8)
    val internal = fields.readShort().toInt() and 0xffff
    val external = fields.readShort().toInt() and 0xffff
    val lifetime = fields.readInt().toLong() and 0xffffffffL
    if (internal != internalPort) return null
    return PortMapping(protocol, internal, external, lifetime)
  }

  /** Sends [request] until a successful reply to [opcode] arrives from [gateway]. */
  private suspend fun exchange(
    gateway: ByteArray,
    request: ByteArray,
    opcode: Int,
    minSize: Int,
  ): ByteArray? {
    val remote = PeerEndpoint(numericHost(gateway), port)
    val socket = try {
      network.bindUdp(PeerEndpoint("0.0.0.0", 0))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return null
    }
    try {
      for (wait in retryMs) {
        try {
          socket.send(remote, request)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return null
        }
        val reply = withTimeoutOrNull(wait) { reply(socket, remote, opcode, minSize) }
        if (reply != null) {
          val result = ((reply[2].toInt() and 0xff) shl 8) or (reply[3].toInt() and 0xff)
          return if (result == 0) reply else null
        }
      }
      return null
    } finally {
      socket.close()
    }
  }

  /** The next datagram from [gateway] answering [opcode]; others are skipped. */
  private suspend fun reply(
    socket: TorrentDatagramSocket,
    gateway: PeerEndpoint,
    opcode: Int,
    minSize: Int,
  ): ByteArray {
    while (true) {
      val datagram = socket.receive()
      val bytes = datagram.bytes
      if (datagram.remote.host != gateway.host || bytes.size < minSize) continue
      if (bytes[0].toInt() == 0 && (bytes[1].toInt() and 0xff) == 128 + opcode) return bytes
    }
  }

  companion object {
    const val NAT_PMP_PORT = 5351
  }
}
