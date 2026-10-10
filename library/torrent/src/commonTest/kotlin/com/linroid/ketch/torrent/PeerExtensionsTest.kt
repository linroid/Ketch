package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerExtensionsTest {
  private fun parse(message: PeerMessage.Extended): Bencode.Node {
    assertEquals(0, message.id)
    return Bencode.parse(message.payload)
  }

  @Test
  fun advertisesListenPortAndRequestQueue() {
    val message = PeerExtensions.handshake(utMetadata = true, metadataSize = 1234, pex = true,
      listenPort = 51413, requestQueue = 64)
    val remote = PeerExtensions()
    remote.receive(message.payload, maxMetadataBytes = 4096)
    assertEquals(51413, remote.listenPort)
    assertEquals(64, remote.requestQueue)
    assertEquals(1234, remote.metadataSize)
    assertEquals(PeerExtensions.METADATA, remote.id("ut_metadata"))
    assertEquals(PeerExtensions.PEX, remote.id("ut_pex"))
    // Before the listener is bound there is no port to tell.
    val unbound = parse(PeerExtensions.handshake(utMetadata = true, metadataSize = 1234,
      pex = false, listenPort = 0, requestQueue = 16))
    assertNull(unbound["p"])
    assertNull(unbound["m"]?.get("ut_pex"))
    assertEquals(16L, unbound["reqq"]?.integer)
  }

  @Test
  fun omitsUtMetadataForPrivateOrDisabledUpload() {
    assertTrue(servesMetadata(privateTorrent = false, TorrentUploadPolicy.WHILE_DOWNLOADING))
    assertTrue(servesMetadata(privateTorrent = false, TorrentUploadPolicy.SEED_AFTER_COMPLETION))
    assertFalse(servesMetadata(privateTorrent = false, TorrentUploadPolicy.DISABLED))
    for (policy in TorrentUploadPolicy.entries) {
      assertFalse(servesMetadata(privateTorrent = true, policy))
    }
    // Withheld, the handshake does not even reveal the info dictionary's size.
    val withheld = parse(PeerExtensions.handshake(utMetadata = false, metadataSize = 1234,
      pex = true, listenPort = 6881, requestQueue = 16))
    assertNull(withheld["m"]?.get("ut_metadata"))
    assertNull(withheld["metadata_size"])
    assertEquals(PeerExtensions.PEX.toLong(), withheld["m"]?.get("ut_pex")?.integer)
    val remote = PeerExtensions()
    remote.receive(PeerExtensions.handshake(utMetadata = false, metadataSize = 1234, pex = true,
      listenPort = 6881, requestQueue = 16).payload, maxMetadataBytes = 4096)
    assertEquals(0, remote.id("ut_metadata"))
    assertNull(remote.metadataSize)
  }

  @Test
  fun ignoresOutOfRangePortAndQueue() {
    val extensions = PeerExtensions()
    for ((port, queue) in listOf<Pair<Any, Any>>(0L to 0L, 65_536L to 2049L, -1L to -5L,
      "6881" to "16")) {
      extensions.receive(Bencode.encode(mapOf("p" to port, "reqq" to queue)), 4096)
      assertNull(extensions.listenPort)
      assertNull(extensions.requestQueue)
    }
    extensions.receive(Bencode.encode(mapOf("p" to 6881L, "reqq" to 2048L)), 4096)
    assertEquals(6881, extensions.listenPort)
    assertEquals(2048, extensions.requestQueue)
    // A later nonsense value keeps what the peer said before.
    extensions.receive(Bencode.encode(mapOf("p" to 0L, "reqq" to 0L)), 4096)
    assertEquals(6881, extensions.listenPort)
    assertEquals(2048, extensions.requestQueue)
  }
}
