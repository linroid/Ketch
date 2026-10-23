package com.linroid.ketch.torrent.tracker

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.logHash
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagramSocket
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.numericHost
import com.linroid.ketch.torrent.net.resolveTorrentHost
import com.linroid.ketch.torrent.trackerLabel
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class TrackerTimeoutException : IllegalStateException("Tracker did not respond")

private val log = KetchLogger("TorrentTracker")

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
  /** The tracker's `warning message`, as [trackerMessage] left it. */
  val warning: String? = null,
)

/**
 * BEP 3/15/41 tracker exchange. Error messages exclude URLs; what a tracker says about a refusal
 * or in a warning is kept only as [trackerMessage] cleans it.
 */
internal class TorrentTracker(
  private val http: TorrentHttp,
  private val network: TorrentNetwork,
  private val resolve: suspend (PeerEndpoint) -> PeerEndpoint = { endpoint ->
    val address = resolveTorrentHost(endpoint.host).firstOrNull()
      ?: throw IOException("Could not resolve the tracker's host")
    PeerEndpoint(numericHost(address), endpoint.port)
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
      "http", "https" -> parseHttp(http.fetch(httpUrl(url, request, trackerId), 1024 * 1024,
        headers = TRACKER_HEADERS), url)
      "udp" -> udp(parsed, url, request)
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
        TrackerScrape.parseHttp(http.fetch(base + separator + query, 64 * 1024,
          headers = TRACKER_HEADERS), topics)
      }
      "udp" -> udpExchange(parsed, url, 2, 8 + topics.size * 12, { packet ->
        topics.forEach { packet.write(it.wireBytes()) }
      }) { bytes -> TrackerScrape.parseUdp(bytes.bytes, topics) }
      else -> error("Unsupported tracker protocol")
    }
  }

  private suspend fun udp(url: Url, trackerUrl: String, request: TrackerAnnounce): TrackerResponse =
    udpExchange(url, trackerUrl, 1, 20, { packet ->
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
    trackerUrl: String,
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
          val connected = response(socket, remote, trackerUrl, connectId, 0, 16)
          val cookie = Buffer().write(connected, 8, 8).readLong()
          val transaction = randomInt()
          val packet = Buffer().writeLong(cookie).writeInt(action).writeInt(transaction)
          encode(packet)
          socket.send(remote, packet.readByteArray())
          val reply = response(socket, remote, trackerUrl, transaction, action, minimum)
          decode(UdpReply(reply, ':' in remote.host))
        }
        if (result != null) return result
      }
      throw TrackerTimeoutException()
    } finally {
      socket.close()
    }
  }

  private suspend fun response(
    socket: TorrentDatagramSocket,
    remote: PeerEndpoint,
    trackerUrl: String,
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
      // BEP 15 errors carry the tracker's message after the header.
      if (receivedAction == 3) {
        val message = reply.bytes.copyOfRange(8, reply.bytes.size)
        throw TrackerFailureException(trackerMessage(message, trackerUrl))
      }
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

    /**
     * Reads [url]'s answer to an announce; its `failure reason` fails it. Read as leniently as
     * other clients read it: trailing whitespace, a missing `interval` (30 minutes then), peers
     * in the original dictionary model or as an empty list, and malformed entries among them,
     * which are skipped.
     */
    fun parseHttp(bytes: ByteArray, url: String): TrackerResponse {
      var end = bytes.size
      while (end > 0 && bytes[end - 1].toInt().toChar() in " \t\r\n") end--
      val root = Bencode.parse(if (end == bytes.size) bytes else bytes.copyOf(end), 1024 * 1024,
        lenient = true)
      require(root.dictionary != null) { "Invalid tracker response" }
      root["failure reason"]?.let { reason ->
        throw TrackerFailureException(reason.bytes?.let { trackerMessage(it, url) })
      }
      val interval = checkedInterval(root["interval"]?.integer ?: DEFAULT_INTERVAL_SECONDS)
      val minimum = root["min interval"]?.integer ?: interval
      require(minimum in 1..604_800)
      val peers = mutableListOf<PeerEndpoint>()
      root["peers"]?.let { peers += peerList(it, ipv6 = false) }
      root["peers6"]?.let { peers += peerList(it, ipv6 = true) }
      val id = root["tracker id"]?.bytes
      require(id == null || id.size <= 1024)
      return TrackerResponse(peers.distinct().take(4096), maxOf(interval, minimum), id,
        minimumIntervalSeconds = minimum,
        warning = root["warning message"]?.bytes?.let { trackerMessage(it, url) })
    }

    fun compactPeers(bytes: ByteArray, ipv6: Boolean): List<PeerEndpoint> =
      compactEntries(bytes, ipv6).filterNotNull().distinct()

    /** A `peers` or `peers6` value: compact, or a list of `{ip, port}` dictionaries. */
    private fun peerList(node: Bencode.Node, ipv6: Boolean): List<PeerEndpoint> {
      node.bytes?.let { return compactPeers(it, ipv6) }
      val list = node.list ?: return emptyList()
      require(list.size <= 4096)
      return list.mapNotNull { item ->
        val host = runCatching { item["ip"]?.text() }.getOrNull()?.takeIf { it.isNotBlank() }
        val port = item["port"]?.integer?.takeIf { it in 1..65535 }
        if (host == null || port == null) null else PeerEndpoint(host, port.toInt())
      }
    }

    /**
     * Every compact entry of [bytes] in order, null where the port is 0, so that values sent
     * beside the entries (such as PEX flags) still line up with them.
     */
    fun compactEntries(bytes: ByteArray, ipv6: Boolean): List<PeerEndpoint?> {
      val stride = if (ipv6) 18 else 6
      require(bytes.size % stride == 0 && bytes.size / stride <= 4096)
      return bytes.indices.step(stride).map { offset ->
        val port = ((bytes[offset + stride - 2].toInt() and 255) shl 8) or
          (bytes[offset + stride - 1].toInt() and 255)
        if (port == 0) null else PeerEndpoint(
          numericHost(bytes.copyOfRange(offset, offset + stride - 2)), port
        )
      }
    }

    private fun checkedInterval(value: Long): Long {
      require(value in 1..604_800) { "Invalid tracker interval" }
      return value
    }

    private fun randomInt(): Int = Buffer().write(torrentRandomBytes(4)).readInt()

    // Trackers that check the peer ID often check this too, whatever engine sends it.
    private val TRACKER_HEADERS = mapOf("User-Agent" to TorrentClient.userAgent)

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
  /**
   * Whether the trackers are ones Ketch adds to public torrents
   * ([com.linroid.ketch.torrent.TorrentConfig.additionalTrackers] and tracker lists), not the
   * torrent's: many serve only some torrents, so their refusals and warnings are logged at debug
   * rather than warn.
   */
  private var added = false

  private val statuses = linkedMapOf<String, TrackerStatus>().apply {
    for (tier in tiers) for (url in tier) {
      if (url !in this) put(url, TrackerStatus(size))
    }
  }
  private var tiers = tiers.map { it.distinct().shuffled().toMutableList() }
  private var configurationRevision = 0L
  private var statusListener: (List<TrackerStatus>) -> Unit = {}
  private val ids = mutableMapOf<String, ByteArray>()
  private var topic: TrackerTopic? = null
  private var preferCurrent = false
  private var current: String? = null
  private val scrapeRetryAt = mutableMapOf<String, Long>()
  private var oldPeersClosed = false
  private var beforeSwitch: suspend () -> Unit = {}
  /**
   * The tracker a canceled `started` announce was sent to, which may count us without having
   * answered: the next `stopped` goes there first, whichever tracker would otherwise be asked.
   */
  private var unconfirmedStart: String? = null

  constructor(
    tiers: List<List<String>>,
    announce: suspend (String, TrackerAnnounce, ByteArray?) -> TrackerResponse,
    revision: Long,
  ) : this(tiers, announce) {
    require(revision >= 0)
    configurationRevision = revision
    for ((url, status) in statuses.toMap()) {
      statuses[url] = status.copy(configurationRevision = revision)
    }
  }

  /** Tiers of trackers Ketch [added] to a public torrent. */
  constructor(
    tiers: List<List<String>>,
    added: Boolean,
    announce: suspend (String, TrackerAnnounce, ByteArray?) -> TrackerResponse,
  ) : this(tiers, announce) {
    this.added = added
  }

  /** Caller serializes replacement with announces. Topic binding is deliberately retained. */
  suspend fun replace(configuration: TrackerConfiguration) {
    check(configurationRevision < Long.MAX_VALUE)
    if (preferCurrent && current != null && !oldPeersClosed) beforeSwitch()
    currentCoroutineContext().ensureActive()
    val replacement = configuration.tiers.map { it.shuffled().toMutableList() }
    tiers = replacement
    configurationRevision++
    ids.clear()
    scrapeRetryAt.clear()
    statuses.clear()
    for (tier in configuration.tiers) for (url in tier) {
      if (url !in statuses) statuses[url] = TrackerStatus(statuses.size, configurationRevision)
    }
    current = null
    oldPeersClosed = false
    unconfirmedStart = null
    statusListener(status())
  }

  /** Read on the session owner; snapshots remain unchanged across later announces. */
  fun status(): List<TrackerStatus> = statuses.values.toList()

  fun observeStatus(listener: (List<TrackerStatus>) -> Unit) {
    statusListener = listener
    listener(status())
  }

  private fun updateStatus(url: String, status: TrackerStatus) {
    statuses[url] = status
    statusListener(this.status())
  }

  private fun failed(url: String, outcome: TrackerStatus.Outcome, reason: String? = null) {
    val previous = statuses.getValue(url)
    updateStatus(url, previous.copy(outcome = outcome, failures = previous.failures + 1,
      consecutiveFailures = previous.consecutiveFailures + 1, failureReason = reason))
  }

  fun preferCurrentTracker(beforeSwitch: suspend () -> Unit = {}) {
    preferCurrent = true
    this.beforeSwitch = beforeSwitch
  }

  /** Serialized with announces; never contacts a fallback tracker just for statistics. */
  suspend fun scrapeCurrent(
    nowMs: Long,
    scrape: suspend (String, List<TrackerTopic>) -> Map<TrackerTopic, TrackerScrape>,
  ): Boolean {
    val url = current ?: return false
    val identity = topic ?: return false
    if (nowMs < (scrapeRetryAt[url] ?: Long.MIN_VALUE)) return false
    // Charge the cooldown before I/O, including failed and canceled attempts.
    scrapeRetryAt[url] = if (nowMs > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else nowMs + 60_000
    updateStatus(url, statuses.getValue(url).copy(
      scrapeOutcome = TrackerStatus.ScrapeOutcome.REQUESTING,
    ))
    try {
      val result = scrape(url, listOf(identity))[identity]
      updateStatus(url, statuses.getValue(url).copy(
        scrapeOutcome = if (result == null) TrackerStatus.ScrapeOutcome.MISSING
          else TrackerStatus.ScrapeOutcome.SUCCEEDED,
        scrape = result,
      ))
      return true
    } catch (error: CancellationException) {
      updateStatus(url, statuses.getValue(url).copy(
        scrapeOutcome = TrackerStatus.ScrapeOutcome.CANCELED,
      ))
      throw error
    } catch (error: Exception) {
      currentCoroutineContext().ensureActive()
      updateStatus(url, statuses.getValue(url).copy(
        scrapeOutcome = TrackerStatus.ScrapeOutcome.FAILED,
      ))
      throw error
    }
  }

  suspend fun announce(request: TrackerAnnounce): TrackerResponse {
    require(topic == null || topic == request.topic) { "Tracker tiers belong to another topic" }
    topic = request.topic
    val first = unconfirmedStart?.takeIf { request.event == TrackerEvent.STOPPED }
      ?: current?.takeIf { preferCurrent }
    val ordered = if (first != null) {
      listOf(listOf(first)) + tiers.map { tier -> tier.filter { it != first } }
    } else tiers
    for (tier in ordered) {
      for (url in tier.toList()) {
        if (preferCurrent && current != null && current != url && !oldPeersClosed) {
          // Cleanup failure must escape, not be treated as a failed tracker candidate.
          beforeSwitch()
          oldPeersClosed = true
        }
        announceCandidate(url, request)?.let { return it }
      }
    }
    error("No tracker responded")
  }

  // Keep the suspending attempt outside the nested tier loops. Combining their back edges with
  // catch/finally produces an irreducible coroutine graph that GraalVM 21 cannot compile.
  private suspend fun announceCandidate(url: String, request: TrackerAnnounce): TrackerResponse? {
    val previous = statuses.getValue(url)
    updateStatus(url, previous.copy(outcome = TrackerStatus.Outcome.ANNOUNCING,
      attempts = previous.attempts + 1))
    try {
      val result = announce(url, request, ids[url])
      unconfirmedStart = null
      result.trackerId?.let { ids[url] = it }
      val original = tiers.first { url in it }
      original.remove(url)
      original.add(0, url)
      current = url
      oldPeersClosed = false
      updateStatus(url, statuses.getValue(url).copy(
        outcome = TrackerStatus.Outcome.SUCCEEDED,
        consecutiveFailures = 0,
        lastPeerCount = result.peers.size,
        lastIntervalSeconds = result.intervalSeconds,
        lastMinimumIntervalSeconds = result.minimumIntervalSeconds,
        failureReason = null,
        warning = result.warning,
      ))
      if (result.warning != null && result.warning != previous.warning) {
        val message = "Tracker ${trackerLabel(url)} warns about ${request.topic.logHash()}: " +
          result.warning
        if (added) log.d { message } else log.w { message }
      }
      log.d {
        "Announce ${request.event} for ${request.topic.logHash()} to ${trackerLabel(url)}: " +
          "peers=${result.peers.size}, interval=${result.intervalSeconds}s"
      }
      return result.copy(source = url)
    } catch (_: TrackerTimeoutException) {
      currentCoroutineContext().ensureActive()
      failed(url, TrackerStatus.Outcome.TIMED_OUT)
      announceFailed(url, request, "timed out")
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      failed(url, TrackerStatus.Outcome.TIMED_OUT)
      announceFailed(url, request, "timed out")
    } catch (e: CancellationException) {
      if (request.event == TrackerEvent.STARTED) unconfirmedStart = url
      throw e
    } catch (e: TrackerFailureException) {
      currentCoroutineContext().ensureActive()
      failed(url, TrackerStatus.Outcome.FAILED, e.reason)
      // Said once at warn: trackers repeat a refusal on every retry until something changes.
      if (e.reason != null && e.reason != previous.failureReason) {
        val message = "Tracker ${trackerLabel(url)} refused announce ${request.event} for " +
          "${request.topic.logHash()}: ${e.reason}"
        if (added) log.d { message } else log.w { message }
      } else {
        announceFailed(url, request, e.describeWithoutUrls())
      }
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      failed(url, TrackerStatus.Outcome.FAILED)
      announceFailed(url, request, e.describeWithoutUrls())
    } finally {
      val latest = statuses.getValue(url)
      if (latest.outcome == TrackerStatus.Outcome.ANNOUNCING) {
        updateStatus(url, latest.copy(outcome = TrackerStatus.Outcome.CANCELED))
      }
    }
    return null
  }

  private fun announceFailed(url: String, request: TrackerAnnounce, reason: String) {
    val failures = statuses.getValue(url).consecutiveFailures
    log.d {
      "Announce ${request.event} for ${request.topic.logHash()} to ${trackerLabel(url)} " +
        "failed ($failures in a row): $reason"
    }
  }
}

/** The announce interval of a reply that names none, as libtorrent assumes. */
private const val DEFAULT_INTERVAL_SECONDS = 1_800L
