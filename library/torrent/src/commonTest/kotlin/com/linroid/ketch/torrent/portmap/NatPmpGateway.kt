package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Buffer

/**
 * A NAT-PMP gateway on loopback, run in [scope]: external address 203.0.113.7, mappings to the
 * internal port plus [offset], or the result code [refuse] when set. Returns the port it answers
 * on.
 */
internal suspend fun natPmpGateway(
  network: TorrentNetwork,
  scope: CoroutineScope,
  offset: Int = 0,
  /** Added to UDP mappings only, as a gateway that gives UDP another port. */
  udpOffset: Int = 0,
  refuse: Int = 0,
  /** Grants mappings without replying, as a gateway whose replies are lost. */
  silentGrants: Boolean = false,
  /** Removes mappings without replying, as a gateway whose replies are lost. */
  silentRemovals: Boolean = false,
  /** Opcode and lifetime of each mapping request; the test reads it while the gateway runs. */
  requests: MutableStateFlow<List<Pair<Int, Int>>> = MutableStateFlow(emptyList()),
): Int {
  val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
  scope.launch {
    try {
      while (true) {
        val datagram = socket.receive()
        val input = Buffer().write(datagram.bytes)
        input.readByte()
        val opcode = input.readByte().toInt()
        val reply = Buffer().writeByte(0).writeByte(128 + opcode).writeShort(refuse).writeInt(1)
        if (opcode == 0) {
          reply.write(byteArrayOf(203.toByte(), 0, 113, 7))
        } else {
          input.readShort()
          val internal = input.readShort().toInt() and 0xffff
          val external = input.readShort().toInt() and 0xffff
          val lifetime = input.readInt()
          requests.update { it + (opcode to lifetime) }
          if (if (lifetime == 0) silentRemovals else silentGrants) continue
          reply.writeShort(internal)
            .writeShort(if (lifetime == 0) 0 else {
              // A port asked for that is not the internal one is granted, as a free port is.
              (external.takeIf { it != 0 && it != internal } ?: (internal + offset)) +
                if (opcode == 1) udpOffset else 0
            })
            .writeInt(lifetime)
        }
        socket.send(datagram.remote, reply.readByteArray())
      }
    } finally {
      socket.close()
    }
  }
  return socket.local.port
}
