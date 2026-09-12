package com.linroid.ketch.torrent

import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
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
  val topic: TrackerTopic,
  val peerId: ByteArray,
  val port: Int,
  val downloaded: Long,
  val left: Long,
  val uploaded: Long = 0,
  val event: TrackerEvent = TrackerEvent.NONE,
  val key: Int = 0,
) {
  constructor(
    infoHash: InfoHash,
    peerId: ByteArray,
    port: Int,
    downloaded: Long,
    left: Long,
    uploaded: Long = 0,
    event: TrackerEvent = TrackerEvent.NONE,
    key: Int = 0,
  ) : this(TrackerTopic.V1(infoHash), peerId, port, downloaded, left, uploaded, event, key)

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
  val minimumIntervalSeconds: Long? = null,
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

  suspend fun scrape(
    url: String,
    requestedTopics: List<TrackerTopic>,
  ): Map<TrackerTopic, TrackerScrape> {
    require(url.length <= 8192 && requestedTopics.size in 1..50)
    val topics = requestedTopics.toList()
    TrackerScrape.validate(topics)
    val parsed = Url(url)
    return when (parsed.protocol.name.lowercase()) {
      "http", "https" -> {
        val path = parsed.encodedPath
        val endpoint = path.lastIndexOf("announce")
        require(endpoint >= 0) { "Tracker has no derived scrape endpoint" }
        val base = URLBuilder(url).apply {
          encodedPath = path.replaceRange(endpoint, endpoint + "announce".length, "scrape")
          fragment = ""
        }.buildString()
        val separator = if ('?' in base) "&" else "?"
        val query = topics.joinToString("&") { "info_hash=" + binaryQuery(it.wireBytes()) }
        TrackerScrape.parseHttp(http.fetch(base + separator + query, 64 * 1024), topics)
      }
      "udp" -> udpExchange(parsed, 2, 8 + topics.size * 12, { packet ->
        topics.forEach { packet.write(it.wireBytes()) }
      }) { bytes -> TrackerScrape.parseUdp(bytes.bytes, topics) }
      else -> error("Unsupported tracker protocol")
    }
  }

  private suspend fun udp(url: Url, request: TrackerAnnounce): TrackerResponse =
    udpExchange(url, 1, 20, { packet ->
      packet.write(request.topic.wireBytes()).write(request.peerId)
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
    }) { reply ->
      val header = Buffer().write(reply.bytes, 8, 12)
      val interval = header.readInt().toLong() and 0xffffffffL
      TrackerResponse(compactPeers(reply.bytes.copyOfRange(20, reply.bytes.size), reply.ipv6),
        checkedInterval(interval))
    }

  private data class UdpReply(val bytes: ByteArray, val ipv6: Boolean)

  private suspend fun <T> udpExchange(
    url: Url,
    action: Int,
    minimum: Int,
    encode: (Buffer) -> Unit,
    decode: (UdpReply) -> T,
  ): T {
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
          val packet = Buffer().writeLong(cookie).writeInt(action).writeInt(transaction)
          encode(packet)
          socket.send(remote, packet.readByteArray())
          val reply = response(socket, remote, transaction, action, minimum)
          decode(UdpReply(reply, ':' in remote.host))
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
      require(receivedAction != 3) { "Tracker rejected request" }
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
        append("info_hash=").append(binaryQuery(request.topic.wireBytes()))
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
      return TrackerResponse(peers.distinct().take(4096), maxOf(interval, minimum), id,
        minimumIntervalSeconds = minimum)
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
  private val statuses = linkedMapOf<String, TrackerStatus>().apply {
    for (tier in tiers) for (url in tier) {
      if (url !in this) put(url, TrackerStatus(size))
    }
  }
  private val tiers = tiers.map { it.distinct().shuffled().toMutableList() }
  private val ids = mutableMapOf<String, ByteArray>()
  private var topic: TrackerTopic? = null
  private var preferCurrent = false
  private var current: String? = null
  private var oldPeersClosed = false
  private var beforeSwitch: suspend () -> Unit = {}

  /** Read on the session owner; snapshots remain unchanged across later announces. */
  fun status(): List<TrackerStatus> = statuses.values.toList()

  private fun failed(url: String, outcome: TrackerStatus.Outcome) {
    val previous = statuses.getValue(url)
    statuses[url] = previous.copy(outcome = outcome, failures = previous.failures + 1,
      consecutiveFailures = previous.consecutiveFailures + 1)
  }

  fun preferCurrentTracker(beforeSwitch: suspend () -> Unit = {}) {
    preferCurrent = true
    this.beforeSwitch = beforeSwitch
  }

  suspend fun announce(request: TrackerAnnounce): TrackerResponse {
    require(topic == null || topic == request.topic) { "Tracker tiers belong to another topic" }
    topic = request.topic
    val ordered = if (preferCurrent && current != null) {
      listOf(listOf(current!!)) + tiers.map { tier -> tier.filter { it != current } }
    } else tiers
    for (tier in ordered) {
      for (url in tier.toList()) {
        if (preferCurrent && current != null && current != url && !oldPeersClosed) {
          // Cleanup failure must escape, not be treated as a failed tracker candidate.
          beforeSwitch()
          oldPeersClosed = true
        }
        val previous = statuses.getValue(url)
        statuses[url] = previous.copy(outcome = TrackerStatus.Outcome.ANNOUNCING,
          attempts = previous.attempts + 1)
        try {
          val result = announce(url, request, ids[url])
          result.trackerId?.let { ids[url] = it }
          val original = tiers.first { url in it }
          original.remove(url)
          original.add(0, url)
          current = url
          oldPeersClosed = false
          statuses[url] = statuses.getValue(url).copy(
            outcome = TrackerStatus.Outcome.SUCCEEDED,
            consecutiveFailures = 0,
            lastPeerCount = result.peers.size,
            lastIntervalSeconds = result.intervalSeconds,
            lastMinimumIntervalSeconds = result.minimumIntervalSeconds,
          )
          return result.copy(source = url)
        } catch (_: TimeoutCancellationException) {
          currentCoroutineContext().ensureActive()
          failed(url, TrackerStatus.Outcome.TIMED_OUT)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          failed(url, TrackerStatus.Outcome.FAILED)
        } finally {
          val latest = statuses.getValue(url)
          if (latest.outcome == TrackerStatus.Outcome.ANNOUNCING) {
            statuses[url] = latest.copy(outcome = TrackerStatus.Outcome.CANCELED)
          }
        }
      }
    }
    error("No tracker responded")
  }
}
