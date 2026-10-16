package com.linroid.ketch.torrent.tracker

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TrackerMessageTest {
  private val request = TrackerAnnounce(InfoHash.fromBytes(ByteArray(20)), TorrentClient.peerId(),
    6881, 0, 1, event = TrackerEvent.STARTED)

  @Test
  fun httpFailureReasonKeepsTheTrackersWordsWithoutThePasskey() = runTest {
    val sent = mutableListOf<Map<String, String>>()
    val engine = object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        error("Unused")

      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        sent += headers
        // Private trackers quote the passkey, and the announce URL, back in their refusals.
        onData(Bencode.encode(mapOf("failure reason" to
          "Unregistered passkey ${PASSKEY.uppercase()}\n\u202Eat $PRIVATE_URL\u0007")))
      }

      override fun close() = Unit
    }
    val network = createTorrentNetwork()
    try {
      val error = assertFailsWith<TrackerFailureException> {
        TorrentTracker(TorrentHttp(engine), network).announce(PRIVATE_URL, request)
      }
      assertEquals("Unregistered passkey *** at https://tracker.example:443", error.reason)
      assertFalse(PASSKEY in error.message.orEmpty().lowercase())
      // Trackers that check the peer ID check the client's User-Agent too.
      assertEquals(TorrentClient.userAgent, sent.single()["User-Agent"])
    } finally {
      network.close()
    }
  }

  @Test
  fun messagesLoseControlCharactersUrlsAndSecrets() {
    val url = "https://user:hunter2@tracker.example/a1b2c3d4e5/announce"
    val raw = "Bad key a1b2c3d4e5 for hunter2;\ttoken Zx9Zx9Zx9Zx9Zx9Zx9 \u200Bok\r\n"
    assertEquals("Bad key *** for ***; token *** ok", trackerMessage(raw.encodeToByteArray(), url))
    // Words of the URL that cannot be secrets, and long words without digits, stay.
    assertEquals("Unregistered announce for Unregistered-torrent",
      trackerMessage("Unregistered announce for Unregistered-torrent".encodeToByteArray(),
        "https://tracker.example/announce"))
    val long = trackerMessage(("Slow down " + "x".repeat(500)).encodeToByteArray(), url)
    assertEquals(MAX_TRACKER_MESSAGE, long?.length)
    assertEquals("Slow down " + "x".repeat(MAX_TRACKER_MESSAGE - 11) + "…", long)
    assertNull(trackerMessage(byteArrayOf(0, 10, 32), url))
  }

  @Test
  fun passkeysEndingAUrlEarlyAreMaskedWhole() {
    // A URL in text ends at a comma, which a passkey may hold; none of it may be left behind.
    val url = "https://tracker.example/announce?passkey=a,bcdef1234"
    assertEquals("Refused https://tracker.example:443 (key ***)",
      trackerMessage("Refused $url (key a,bcdef1234)".encodeToByteArray(), url))
  }

  @Test
  fun httpWarningIsKept() {
    val response = TorrentTracker.parseHttp(Bencode.encode(mapOf("interval" to 60L,
      "peers" to ByteArray(0), "warning message" to "Update\tyour client, $PASSKEY")),
      PRIVATE_URL)
    assertEquals("Update your client, ***", response.warning)
    val refused = assertFailsWith<TrackerFailureException> {
      TorrentTracker.parseHttp(Bencode.encode(mapOf("failure reason" to "\u0000 ")), PRIVATE_URL)
    }
    assertNull(refused.reason)
    assertEquals("Tracker rejected the request", refused.message)
  }

  @Test
  fun statusKeepsTheReasonUntilTheNextAnswer() = runTest {
    var reply: () -> TrackerResponse = { throw TrackerFailureException("Torrent not registered") }
    val tiers = TrackerTiers(listOf(listOf(PRIVATE_URL))) { _, _, _ -> reply() }
    assertFailsWith<IllegalStateException> { tiers.announce(request) }
    tiers.status().single().let { status ->
      assertEquals(TrackerStatus.Outcome.FAILED, status.outcome)
      assertEquals("Torrent not registered", status.failureReason)
    }
    reply = { TrackerResponse(emptyList(), 60, warning = "Client outdated") }
    tiers.announce(request)
    tiers.status().single().let { status ->
      assertEquals(TrackerStatus.Outcome.SUCCEEDED, status.outcome)
      assertNull(status.failureReason)
      assertEquals("Client outdated", status.warning)
    }
    reply = { throw IOException("Connection reset") }
    assertFailsWith<IllegalStateException> { tiers.announce(request) }
    assertNull(tiers.status().single().failureReason)
  }

  @Test
  fun udpErrorKeepsTheTrackersWordsWithoutThePasskey() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val network = createTorrentNetwork()
          val http = TorrentHttp.default()
          val socket = network.bindUdp(PeerEndpoint("127.0.0.1", 0))
          try {
            val url = "udp://127.0.0.1:${socket.local.port}/announce?passkey=$PASSKEY"
            val server = async {
              val connect = socket.receive()
              val transaction = Buffer().write(connect.bytes, 12, 4).readInt()
              // BEP 15: action 3, the transaction, then the message.
              socket.send(connect.remote, Buffer().writeInt(3).writeInt(transaction)
                .writeUtf8("Passkey $PASSKEY\u0000 is banned").readByteArray())
            }
            val error = assertFailsWith<TrackerFailureException> {
              TorrentTracker(http, network, retryDelaysMs = listOf(5000)).announce(url, request)
            }
            assertEquals("Passkey *** is banned", error.reason)
            server.await()
          } finally {
            socket.close()
            network.close()
            http.close()
          }
        }
      }
    }
  }

  private companion object {
    const val PASSKEY = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    const val PRIVATE_URL = "https://tracker.example/announce.php?passkey=$PASSKEY&uid=4242"
  }
}
