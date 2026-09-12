package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackerScrapeTest {
  private val v1 = TrackerTopic.V1(InfoHash.fromBytes(ByteArray(20) { it.toByte() }))
  private val v2 = TrackerTopic.V2(V2InfoHash.fromBytes(ByteArray(32) { (255 - it).toByte() }))
  private val topics = listOf(v1, v2)

  private class Engine(val bytes: ByteArray) : HttpEngine {
    var requested: String? = null
    var onFetch: () -> Unit = {}
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) { requested = url; onFetch(); onData(bytes) }
    override fun close() = Unit
  }

  private fun response(complete: Long = 7): ByteArray = Bencode.encode(mapOf("files" to mapOf(
    v2.wireBytes().toByteString() to mapOf("complete" to complete, "downloaded" to 9L,
      "incomplete" to 11L))))

  @Test
  fun httpPreservesPasskeyUsesBinaryHashAndDoesNotInventMissingCounts() = runTest {
    val engine = Engine(response())
    val network = createTorrentNetwork()
    try {
      val result = TorrentTracker(TorrentHttp(engine), network).scrape(
        "https://tracker/announce-proxy/v1/announce.php?passkey=a%2Bb#ignored", topics)
      val url = engine.requested!!
      assertTrue(url.startsWith("https://tracker/announce-proxy/v1/scrape.php?passkey=a%2Bb&"))
      assertTrue("info_hash=%00%01%02" in url)
      assertTrue("&info_hash=%ff%fe%fd" in url)
      assertTrue('#' !in url)
      assertEquals(mapOf<TrackerTopic, TrackerScrape>(v2 to TrackerScrape(7, 9, 11)), result)
    } finally { network.close() }
  }

  @Test
  fun responseMappingKeepsTheTopicsSentBeforeSuspension() = runTest {
    val requested = mutableListOf<TrackerTopic>(v2)
    val engine = Engine(response()).apply { onFetch = { requested.clear() } }
    val network = createTorrentNetwork()
    try {
      val result = TorrentTracker(TorrentHttp(engine), network)
        .scrape("https://tracker/announce", requested)
      assertEquals(TrackerScrape(7, 9, 11), result[v2])
      assertEquals(1, result.size)
    } finally { network.close() }
  }

  @Test
  fun rejectsUnboundedAndAmbiguousQueriesBeforeNetworkUse() = runTest {
    val engine = Engine(response())
    val network = createTorrentNetwork()
    try {
      val tracker = TorrentTracker(TorrentHttp(engine), network)
      for (invalid in listOf(emptyList(), List(51) { v1 }, listOf(v1, v1),
        listOf(v1, TrackerTopic.V2(V2InfoHash.fromBytes(v1.wireBytes() + ByteArray(12)))))) {
        assertFailsWith<IllegalArgumentException> { tracker.scrape("https://t/announce", invalid) }
      }
      assertFailsWith<IllegalArgumentException> {
        tracker.scrape("https://t/stats?announce=1", topics)
      }
      assertEquals(null, engine.requested)
    } finally { network.close() }
  }

  @Test
  fun rejectsMalformedCountsAndSanitizesTrackerErrors() {
    assertFailsWith<IllegalArgumentException> { TrackerScrape.parseHttp(response(-1), topics) }
    for (key in listOf("failure reason", "failure_reason")) {
      val error = assertFailsWith<IllegalArgumentException> {
        TrackerScrape.parseHttp(Bencode.encode(mapOf(key to "secret passkey")), topics)
      }
      assertEquals("Tracker rejected scrape", error.message)
    }
    assertFailsWith<IllegalArgumentException> { TrackerScrape.parseHttp(ByteArray(65_537), topics) }
    assertFailsWith<IllegalArgumentException> { TrackerScrape.parseUdp(ByteArray(31), topics) }
  }

  private class SilentScrapeNetwork : TorrentNetwork {
    var connects = 0
    var scrapes = 0
    var closed = false
    private val replies = Channel<TorrentDatagram>(1)
    private val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("127.0.0.1", 1234)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) {
        val packet = Buffer().write(bytes)
        packet.readLong()
        val action = packet.readInt()
        val transaction = packet.readInt()
        if (action == 0) {
          connects++
          replies.send(TorrentDatagram(remote, Buffer().writeInt(0).writeInt(transaction)
            .writeLong(connects.toLong()).readByteArray()))
        } else {
          assertEquals(2, action)
          assertEquals(connects.toLong(), Buffer().write(bytes).readLong())
          scrapes++
        }
      }
      override suspend fun receive(): TorrentDatagram = replies.receive()
      override fun close() { closed = true; replies.cancel() }
    }
    override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = socket
    override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
    override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
    override fun close() = socket.close()
  }

  @Test
  fun scrapeRetriesWithFreshCookiesAndClosesAfterExhaustion() = runTest {
    val network = SilentScrapeNetwork()
    val tracker = TorrentTracker(TorrentHttp(Engine(response())), network, resolve = { it },
      retryDelaysMs = listOf(10, 20))
    val error = assertFailsWith<IllegalStateException> {
      tracker.scrape("udp://127.0.0.1:80", topics)
    }
    assertEquals("Tracker did not respond", error.message)
    assertEquals(2, network.connects)
    assertEquals(2, network.scrapes)
    assertTrue(network.closed)
  }

  @Test
  fun cancellationClosesTheScrapeSocketWithoutRetrying() = runTest {
    val network = SilentScrapeNetwork()
    val tracker = TorrentTracker(TorrentHttp(Engine(response())), network, resolve = { it },
      retryDelaysMs = listOf(100, 200))
    assertFailsWith<TimeoutCancellationException> {
      withTimeout(5) { tracker.scrape("udp://127.0.0.1:80", topics) }
    }
    assertEquals(1, network.connects)
    assertTrue(network.closed)
  }

  @Test
  fun udpIpv4ValidatesSourceAndTransactionAndMapsUnsignedCountsInRequestOrder() = udp(false)

  @Test
  fun udpIpv6ValidatesSourceAndTransactionAndMapsUnsignedCountsInRequestOrder() = udp(true)

  private fun udp(ipv6: Boolean) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val network = createTorrentNetwork()
        val host = if (ipv6) "::1" else "127.0.0.1"
        val socket = network.bindUdp(PeerEndpoint(host, 0))
        val http = TorrentHttp.default()
        try {
          val server = async {
            val first = socket.receive()
            val connect = Buffer().write(first.bytes)
            assertEquals(0x41727101980L, connect.readLong())
            assertEquals(0, connect.readInt())
            socket.send(first.remote, Buffer().writeInt(0).writeInt(connect.readInt())
              .writeLong(123).readByteArray())
            val request = socket.receive()
            val packet = Buffer().write(request.bytes)
            assertEquals(123L, packet.readLong())
            assertEquals(2, packet.readInt())
            val transaction = packet.readInt()
            assertContentEquals(v1.wireBytes() + v2.wireBytes(), packet.readByteArray())
            fun reply(id: Int, complete: Int = -1): ByteArray = Buffer().writeInt(2).writeInt(id)
              .writeInt(complete).writeInt(2).writeInt(3).writeInt(4).writeInt(5).writeInt(6)
              .writeByte(99).readByteArray()
            val impostor = network.bindUdp(PeerEndpoint(host, 0))
            try {
              impostor.send(request.remote, reply(transaction, 999))
            } finally { impostor.close() }
            socket.send(request.remote, reply(transaction + 1, 999))
            socket.send(request.remote, reply(transaction))
          }
          val urlHost = if (ipv6) "[::1]" else host
          val result = TorrentTracker(http, network, retryDelaysMs = listOf(5000))
            .scrape("udp://$urlHost:${socket.local.port}/announce", topics)
          assertEquals(TrackerScrape(4_294_967_295L, 2, 3), result[v1])
          assertEquals(TrackerScrape(4, 5, 6), result[v2])
          server.await()
        } finally { socket.close(); network.close(); http.close() }
      }
    }
  }
}
