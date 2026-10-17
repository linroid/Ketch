package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.TorrentClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerClientsTest {
  private fun id(prefix: String): ByteArray =
    (prefix + "0".repeat(20 - prefix.length)).encodeToByteArray()

  @Test
  fun namesAzureusStyleClients() {
    assertEquals("qBittorrent 4.6.3", PeerClients.fromPeerId(id("-qB4630-")))
    assertEquals("Transmission 4.0.6", PeerClients.fromPeerId(id("-TR4060-")))
    assertEquals("µTorrent 3.5.5", PeerClients.fromPeerId(id("-UT355W-")))
    // Letters count from 10: Deluge 1.3.15, libtorrent 0.13.6.
    assertEquals("Deluge 1.3.15", PeerClients.fromPeerId(id("-DE13F0-")))
    assertEquals("libtorrent 0.13.6", PeerClients.fromPeerId(id("-lt0D60-")))
    assertEquals("libtorrent (rakshasa) 0.13.6", PeerClients.fromPeerId(id("-LT0D60-")))
    assertEquals("KTorrent 5.1.0", PeerClients.fromPeerId(id("-KT5100-")))
    // A version that does not decode still names the client.
    assertEquals("BiglyBT", PeerClients.fromPeerId(id("-BI!!!!-")))
  }

  @Test
  fun namesOurOwnPeerIds() {
    val name = PeerClients.fromPeerId(TorrentClient.peerId(TorrentClient.peerIdPrefix("0.9.1")))
    assertEquals("Ketch 0.9.1", name)
  }

  @Test
  fun namesShadowAndMainlineStyleClients() {
    assertEquals("BitTornado 0.3.18", PeerClients.fromPeerId(id("T03I-----")))
    assertEquals("ABC 2.0.5", PeerClients.fromPeerId(id("A205-----")))
    assertEquals("Mainline 7.4.3", PeerClients.fromPeerId(id("M7-4-3--")))
    assertEquals("Mainline 4.10.0", PeerClients.fromPeerId(id("M4-10-0-")))
  }

  @Test
  fun unknownIdsNameNothing() {
    assertNull(PeerClients.fromPeerId(id("-ZZ1234-")))
    assertNull(PeerClients.fromPeerId(id("exbc")))
    assertNull(PeerClients.fromPeerId(id("T03I-")))
    assertNull(PeerClients.fromPeerId(ByteArray(20)))
    assertNull(PeerClients.fromPeerId(ByteArray(19)))
  }

  @Test
  fun cleansUpVersionNames() {
    assertEquals("qBittorrent/4.6.3", PeerClients.fromVersion("qBittorrent/4.6.3".bytes()))
    // Control characters become spaces, bidi overrides and other formatting go.
    assertEquals("Evil Client", PeerClients.fromVersion("Evil\n\u0000Client‮".bytes()))
    assertEquals("Spaced out", PeerClients.fromVersion("  Spaced \t  out  ".bytes()))
    assertNull(PeerClients.fromVersion("​\r\n".bytes()))
    // Format characters outside the BMP go too, while other ones stay whole.
    assertNull(PeerClients.fromVersion("\uDB40\uDC7F\uDB40\uDC01".bytes()))
    assertEquals("Tag \uD83D\uDE00",
      PeerClients.fromVersion("Tag \uDB40\uDC41\uD83D\uDE00".bytes()))
    assertNull(PeerClients.fromVersion(ByteArray(0)))
    val long = PeerClients.fromVersion("x".repeat(500).bytes())
    assertEquals(PeerClients.MAX_NAME, long?.length)
    // A cut never splits a surrogate pair.
    val emoji = PeerClients.fromVersion(("a" + "😀".repeat(40)).bytes())
    assertEquals(PeerClients.MAX_NAME - 1, emoji?.length)
  }

  @Test
  fun extensionHandshakeNamesTheClient() {
    val extensions = PeerExtensions()
    assertNull(extensions.client)
    extensions.receive(PeerExtensions.handshake(utMetadata = false, metadataSize = null,
      pex = false, listenPort = 0, requestQueue = 16).payload, 4096)
    assertEquals(TorrentClient.name, extensions.client)
  }

  private fun String.bytes() = encodeToByteArray()
}
