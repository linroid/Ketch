package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import okio.Buffer

/** The BEP 10 name of the holepunch extension (BEP 55). */
internal const val HOLEPUNCH_EXTENSION = "ut_holepunch"

/** What a holepunch message asks (BEP 55 `msg_type`). */
internal enum class HolepunchType(val code: Int) {
  /** To a relay: introduce us to the peer at the endpoint. */
  RENDEZVOUS(0),

  /** From a relay: dial the peer at the endpoint over uTP now; it dials us at the same time. */
  CONNECT(1),

  /** From a relay: the rendezvous for the endpoint failed, as [HolepunchMessage.error] says. */
  ERROR(2),
}

/** BEP 55 error codes. */
internal object HolepunchError {
  const val NO_SUCH_PEER = 1
  const val NOT_CONNECTED = 2
  const val NO_SUPPORT = 3
  const val NO_SELF = 4
}

/**
 * One BEP 55 message: its [type], the peer [endpoint] it names and, for an error, [error].
 * On the wire: `msg_type`, `addr_type` (0 IPv4, 1 IPv6), the address, the port and `err_code`,
 * big-endian, `err_code` 0 outside errors.
 */
internal data class HolepunchMessage(
  val type: HolepunchType,
  val endpoint: PeerEndpoint,
  val error: Int = 0,
) {
  fun encode(): ByteArray {
    val address = requireNotNull(numericAddress(endpoint.host)) { "Holepunch needs an IP address" }
    return Buffer()
      .writeByte(type.code)
      .writeByte(if (address.size == 4) 0 else 1)
      .write(address)
      .writeShort(endpoint.port)
      .writeInt(error)
      .readByteArray()
  }

  companion object {
    /**
     * Reads one message; `null` for a type or address type we do not know, or a payload too
     * short for its address, which the receiver ignores rather than closing the peer.
     */
    fun decode(bytes: ByteArray): HolepunchMessage? {
      if (bytes.size < 2) return null
      val type = HolepunchType.entries.firstOrNull { it.code == bytes[0].toInt() and 0xff }
        ?: return null
      val size = when (bytes[1].toInt()) {
        0 -> 4
        1 -> 16
        else -> return null
      }
      if (bytes.size < 2 + size + 2) return null
      val input = Buffer().write(bytes, 2, bytes.size - 2)
      val address = input.readByteArray(size.toLong())
      val port = input.readShort().toInt() and 0xffff
      if (port == 0) return null
      // Some clients leave err_code out of messages that are not errors.
      val error = if (input.size >= 4) input.readInt() else 0
      return HolepunchMessage(type, PeerEndpoint(numericHost(address), port), error)
    }
  }
}
