package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.numericHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer

/** A PCP mapping: the [mapping] itself and the external address the gateway assigned. */
internal class PcpMapping(val mapping: PortMapping, val externalAddress: ByteArray)

/**
 * PCP (RFC 6887), NAT-PMP's successor on the same port: a MAP request asks a gateway to forward
 * an external port to [map]'s `client` address. Newer routers, and some carrier-grade NATs, speak
 * it rather than NAT-PMP. A request names the client address the gateway must see: our own on
 * the gateway's network, or, for a gateway outside another NAT, that NAT's external address.
 * Requests are sent again after 250, 500 and 1000 ms, and only replies from the gateway asked
 * count.
 */
internal class PcpClient(
  private val network: TorrentNetwork,
  private val port: Int = NatPmpClient.NAT_PMP_PORT,
  private val retryMs: List<Long> = listOf(250, 500, 1000),
) {
  /**
   * Maps [internalPort] of [client] (IPv4) for [lifetimeSeconds], asking for [externalPort]; the
   * gateway may pick another. A lifetime of 0 removes the mapping that [nonce] made. Null when
   * the gateway refuses or is silent.
   */
  suspend fun map(
    gateway: ByteArray,
    client: ByteArray,
    protocol: MappedProtocol,
    internalPort: Int,
    externalPort: Int,
    lifetimeSeconds: Long,
    nonce: ByteArray,
  ): PcpMapping? {
    require(client.size == 4 && nonce.size == NONCE_BYTES)
    require(internalPort in 1..65535 && externalPort in 0..65535 && lifetimeSeconds >= 0)
    val request = Buffer()
      .writeByte(VERSION).writeByte(OPCODE_MAP).writeShort(0)
      .writeInt(lifetimeSeconds.toInt())
      .write(ipv4Mapped(client))
      .write(nonce)
      .writeByte(protocol.ianaNumber).writeByte(0).writeShort(0)
      .writeShort(internalPort).writeShort(externalPort)
      // Any external address the gateway has, of the IPv4 family.
      .write(ipv4Mapped(ByteArray(4)))
      .readByteArray()
    val reply = exchange(gateway, request) ?: return null
    val body = Buffer().write(reply, HEADER_BYTES, MAP_BYTES)
    val replyNonce = body.readByteArray(NONCE_BYTES.toLong())
    val replyProtocol = body.readByte().toInt() and 0xff
    body.skip(3)
    val internal = body.readShort().toInt() and 0xffff
    val external = body.readShort().toInt() and 0xffff
    val address = body.readByteArray(16).copyOfRange(12, 16)
    if (!replyNonce.contentEquals(nonce) || replyProtocol != protocol.ianaNumber ||
      internal != internalPort) return null
    val lifetime = Buffer().write(reply, 4, 4).readInt().toLong() and 0xffffffffL
    return PcpMapping(PortMapping(protocol, internal, external, lifetime), address)
  }

  /** Sends [request] until a successful MAP reply arrives from [gateway]. */
  private suspend fun exchange(gateway: ByteArray, request: ByteArray): ByteArray? {
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
        val reply = withTimeoutOrNull(wait) { reply(socket, remote) }
        if (reply != null) return reply.takeIf { (it[3].toInt() and 0xff) == RESULT_SUCCESS }
      }
      return null
    } finally {
      socket.close()
    }
  }

  /** The next MAP reply from [gateway]; others, NAT-PMP's included, are skipped. */
  private suspend fun reply(socket: TorrentDatagramSocket, gateway: PeerEndpoint): ByteArray {
    while (true) {
      val datagram = socket.receive()
      val bytes = datagram.bytes
      if (datagram.remote.host != gateway.host || bytes.size < HEADER_BYTES + MAP_BYTES) continue
      if (bytes[0].toInt() == VERSION && (bytes[1].toInt() and 0xff) == (0x80 or OPCODE_MAP)) {
        return bytes
      }
    }
  }

  companion object {
    const val NONCE_BYTES = 12

    /** A fresh mapping nonce; refreshing or removing a mapping repeats the one that made it. */
    fun nonce(): ByteArray = torrentRandomBytes(NONCE_BYTES)

    private const val VERSION = 2
    private const val OPCODE_MAP = 1
    private const val RESULT_SUCCESS = 0
    private const val HEADER_BYTES = 24
    private const val MAP_BYTES = 36

    private fun ipv4Mapped(address: ByteArray): ByteArray =
      ByteArray(10) + byteArrayOf(-1, -1) + address
  }
}
