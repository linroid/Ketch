package com.linroid.ketch.torrent

import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import kotlin.coroutines.cancellation.CancellationException

internal enum class TrackerEvent(val code: Int) {
  NONE(0), COMPLETED(1), STARTED(2), STOPPED(3)
}

internal data class TrackerAnnounce(
  val infoHash: InfoHash,
  val peerId: ByteArray,
  val port: Int,
  val downloaded: Long,
  val left: Long,
  val uploaded: Long = 0,
  val event: TrackerEvent = TrackerEvent.NONE,
  val key: Int = 0,
) {
  init {
    require(peerId.size == 20 && port in 1..65535)
    require(downloaded >= 0 && left >= 0 && uploaded >= 0)
  }
}

internal data class TrackerResponse(
  val peers: List<PeerEndpoint>,
  val intervalSeconds: Long,
  val trackerId: ByteArray? = null,
  val source: String? = null,
)

/** BEP 3/15/41 tracker exchange. Error messages deliberately exclude URLs and tracker text. */
internal class TorrentTracker(
  private val http: TorrentHttp,
  private val network: TorrentNetwork,
  private val resolve: suspend (PeerEndpoint) -> PeerEndpoint = { endpoint ->
    withContext(Dispatchers.IO) {
      PeerEndpoint(numericHost(checkNotNull(
        InetSocketAddress(endpoint.host, endpoint.port).resolveAddress()
      )), endpoint.port)
    }
  },
  private val retryDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000),
) {
  init {
    require(retryDelaysMs.isNotEmpty() && retryDelaysMs.size <= 9)
    require(retryDelaysMs.all { it in 1..3_840_000 })
  }

  suspend fun announce(
    url: String,
    request: TrackerAnnounce,
    trackerId: ByteArray? = null,
  ): TrackerResponse {
    require(url.length <= 8192)
    val parsed = Url(url)
    return when (parsed.protocol.name.lowercase()) {
      "http", "https" -> parseHttp(http.fetch(httpUrl(url, request, trackerId), 1024 * 1024))
      "udp" -> udp(parsed, request)
      else -> error("Unsupported tracker protocol")
    }
  }

  private suspend fun udp(url: Url, request: TrackerAnnounce): TrackerResponse {
    require(url.port in 1..65535 && url.user == null && url.password == null)
    val remote = resolve(PeerEndpoint(url.host.removeSurrounding("[", "]"), url.port))
    val socket = network.bindUdp(PeerEndpoint(if (':' in remote.host) "::" else "0.0.0.0", 0))
    try {
      // Each retry obtains a new connection ID, so expired cookies are never reused.
      for (timeout in retryDelaysMs) {
        val result = withTimeoutOrNull(timeout) {
          val connectId = randomInt()
          val connect = Buffer().writeLong(0x41727101980L).writeInt(0).writeInt(connectId)
          socket.send(remote, connect.readByteArray())
          val connected = response(socket, remote, connectId, 0, 16)
          val cookie = Buffer().write(connected, 8, 8).readLong()
          val transaction = randomInt()
          val packet = Buffer().writeLong(cookie).writeInt(1).writeInt(transaction)
            .write(request.infoHash.toBytes()).write(request.peerId)
            .writeLong(request.downloaded).writeLong(request.left).writeLong(request.uploaded)
            .writeInt(request.event.code).writeInt(0).writeInt(request.key).writeInt(200)
            .writeShort(request.port)
          val path = url.encodedPathAndQuery.encodeToByteArray()
          if (path.isNotEmpty() && !path.contentEquals(byteArrayOf('/'.code.toByte()))) {
            var offset = 0
            while (offset < path.size) {
              val length = minOf(255, path.size - offset)
              packet.writeByte(2).writeByte(length).write(path, offset, length)
              offset += length
            }
            packet.writeByte(0)
          }
          socket.send(remote, packet.readByteArray())
          val reply = response(socket, remote, transaction, 1, 20)
          val header = Buffer().write(reply, 8, 12)
          val interval = header.readInt().toLong() and 0xffffffffL
          TrackerResponse(compactPeers(reply.copyOfRange(20, reply.size), ':' in remote.host),
            checkedInterval(interval))
        }
        if (result != null) return result
      }
      error("Tracker did not respond")
    } finally {
      socket.close()
    }
  }

  private suspend fun response(
    socket: TorrentDatagramSocket,
    remote: PeerEndpoint,
    transaction: Int,
    action: Int,
    minimum: Int,
  ): ByteArray {
    while (true) {
      currentCoroutineContext().ensureActive()
      val reply = socket.receive()
      if (reply.remote != remote || reply.bytes.size < 8) continue
      val header = Buffer().write(reply.bytes, 0, 8)
      val receivedAction = header.readInt()
      if (header.readInt() != transaction) continue
      require(receivedAction != 3) { "Tracker rejected announce" }
      if (receivedAction != action || reply.bytes.size < minimum) continue
      return reply.bytes
    }
  }

  companion object {
    fun httpUrl(url: String, request: TrackerAnnounce, trackerId: ByteArray?): String {
      val base = url.substringBefore('#')
      val separator = if ('?' in base) "&" else "?"
      return buildString {
        append(base).append(separator)
        append("info_hash=").append(binaryQuery(request.infoHash.toBytes()))
        append("&peer_id=").append(binaryQuery(request.peerId))
        append("&port=").append(request.port)
        append("&downloaded=").append(request.downloaded)
        append("&left=").append(request.left)
        append("&uploaded=").append(request.uploaded)
        append("&compact=1&numwant=200&key=").append(request.key.toUInt())
        if (request.event != TrackerEvent.NONE) {
          append("&event=").append(request.event.name.lowercase())
        }
        if (trackerId != null) append("&trackerid=").append(binaryQuery(trackerId))
      }
    }

    fun parseHttp(bytes: ByteArray): TrackerResponse {
      val root = Bencode.parse(bytes, 1024 * 1024)
      require(root.dictionary != null && root["failure reason"] == null) {
        "Tracker rejected announce"
      }
      val interval = checkedInterval(requireNotNull(root["interval"]?.integer))
      val minimum = root["min interval"]?.integer ?: interval
      require(minimum in 1..604_800)
      val peers = mutableListOf<PeerEndpoint>()
      root["peers"]?.let { node ->
        if (node.bytes != null) peers += compactPeers(node.bytes!!, false)
        else {
          val list = requireNotNull(node.list)
          require(list.size <= 4096)
          for (item in list) {
            val host = requireNotNull(item["ip"]?.text())
            val port = requireNotNull(item["port"]?.integer)
            require(port in 1..65535)
            peers += PeerEndpoint(host, port.toInt())
          }
        }
      }
      root["peers6"]?.let { peers += compactPeers(requireNotNull(it.bytes), true) }
      val id = root["tracker id"]?.bytes
      require(id == null || id.size <= 1024)
      return TrackerResponse(peers.distinct().take(4096), maxOf(interval, minimum), id)
    }

    fun compactPeers(bytes: ByteArray, ipv6: Boolean): List<PeerEndpoint> {
      val stride = if (ipv6) 18 else 6
      require(bytes.size % stride == 0 && bytes.size / stride <= 4096)
      return bytes.indices.step(stride).mapNotNull { offset ->
        val port = ((bytes[offset + stride - 2].toInt() and 255) shl 8) or
          (bytes[offset + stride - 1].toInt() and 255)
        if (port == 0) null else PeerEndpoint(
          numericHost(bytes.copyOfRange(offset, offset + stride - 2)), port
        )
      }.distinct()
    }

    private fun checkedInterval(value: Long): Long {
      require(value in 1..604_800) { "Invalid tracker interval" }
      return value
    }

    private fun randomInt(): Int = Buffer().write(torrentRandomBytes(4)).readInt()

    private fun binaryQuery(bytes: ByteArray): String = bytes.joinToString("") {
      "%" + (it.toInt() and 255).toString(16).padStart(2, '0')
    }
  }
}

/** BEP 12: exhaust a tier before falling back; promote a successful tracker within its tier. */
internal class TrackerTiers(
  tiers: List<List<String>>,
  private val announce: suspend (String, TrackerAnnounce, ByteArray?) -> TrackerResponse,
) {
  private val tiers = tiers.map { it.distinct().shuffled().toMutableList() }
  private val ids = mutableMapOf<String, ByteArray>()

  suspend fun announce(request: TrackerAnnounce): TrackerResponse {
    for (tier in tiers) {
      for (url in tier.toList()) {
        try {
          val result = announce(url, request, ids[url])
          result.trackerId?.let { ids[url] = it }
          tier.remove(url)
          tier.add(0, url)
          return result.copy(source = url)
        } catch (_: TimeoutCancellationException) {
          currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
        }
      }
    }
    error("No tracker responded")
  }
}
