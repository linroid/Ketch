package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.MultiFileTorrent
import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.magnet.MagnetUri
import com.linroid.ketch.torrent.metainfo.Bencode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a task's torrent exports as: its magnet link, info hashes and `.torrent` file. */
class TorrentExportsTest {
  private val config = TorrentConfig()
  private val tracker = "http://tracker.example/announce"

  @Test
  fun `a v1 torrent exports its hash name and trackers`() {
    val torrent = MultiFileTorrent(announce = tracker)
    val export = assertNotNull(
      exportOf("t1", "torrent:x", torrent.metainfo, includeMetainfo = false, config)
    )
    assertEquals(torrent.metadata.infoHash.hex, export.infoHash)
    assertNull(export.infoHashV2)
    assertEquals("pack", export.name)
    assertFalse(export.privateTorrent)
    assertNull(export.metainfo)
    val magnet = MagnetUri.parse(assertNotNull(export.magnet))
    assertEquals(torrent.metadata.infoHash, magnet.identity.v1)
    assertNull(magnet.identity.v2)
    assertEquals("pack", magnet.displayName)
    assertEquals(listOf(tracker), magnet.trackers)
  }

  @Test
  fun `the torrent file is exported only when asked for`() {
    val torrent = MultiFileTorrent()
    val export = assertNotNull(
      exportOf("t1", "torrent:x", torrent.metainfo, includeMetainfo = true, config)
    )
    assertContentEquals(torrent.metainfo, export.metainfoBytes())
    assertFalse("metainfo=${export.metainfo}" in export.toString())
  }

  @Test
  fun `a v2 torrent exports its full hash and web seeds`() {
    val fixture = TorrentV2Fixture.build(
      files = listOf("a" to 40_000),
      webSeeds = listOf("https://mirror.example/pack"),
    )
    val export = assertNotNull(
      exportOf("t1", "torrent:x", fixture.metainfo, includeMetainfo = false, config)
    )
    assertNull(export.infoHash)
    assertEquals(fixture.document.info.hash.hex, export.infoHashV2)
    val magnet = MagnetUri.parse(assertNotNull(export.magnet))
    assertNull(magnet.identity.v1)
    assertEquals(fixture.document.info.hash, magnet.identity.v2)
    assertEquals(listOf("https://mirror.example/pack"), magnet.webSeeds)
  }

  @Test
  fun `a hybrid torrent exports both hashes`() {
    val fixture = TorrentV2Fixture.build(files = listOf("a" to 40_000), hybrid = true)
    val export = assertNotNull(
      exportOf("t1", "torrent:x", fixture.metainfo, includeMetainfo = false, config)
    )
    val identity = fixture.document.identity
    assertEquals(assertNotNull(identity.v1).hex, export.infoHash)
    assertEquals(assertNotNull(identity.v2).hex, export.infoHashV2)
    assertEquals(identity, MagnetUri.parse(assertNotNull(export.magnet)).identity)
  }

  @Test
  fun `a private torrent has no magnet link`() {
    val torrent = MultiFileTorrent()
    val root = Bencode.parse(torrent.metainfo)
    val info = checkNotNull(root["info"]?.dictionary).entries
      .associate { it.key.utf8() to it.value } + ("private" to 1L)
    val metainfo = Bencode.encode(
      mapOf("announce" to "$tracker?passkey=secret", "info" to info)
    )
    val export = assertNotNull(
      exportOf("t1", "torrent:x", metainfo, includeMetainfo = true, config)
    )
    assertTrue(export.privateTorrent)
    assertNull(export.magnet)
    assertContentEquals(metainfo, export.metainfoBytes())

    val v2 = TorrentV2Fixture.build(files = listOf("a" to 40_000), privateTorrent = true)
    val v2Export = assertNotNull(
      exportOf("t1", "torrent:x", v2.metainfo, includeMetainfo = false, config)
    )
    assertTrue(v2Export.privateTorrent)
    assertNull(v2Export.magnet)
  }

  @Test
  fun `a magnet without metadata exports its link without peers or selection`() {
    val hash = "a".repeat(40)
    val url = "magnet:?xt=urn:btih:$hash&dn=pack&tr=http%3A%2F%2Ftracker.example%2Fannounce" +
      "&x.pe=192.0.2.1%3A6881&so=0,2"
    val export = assertNotNull(exportOf("t1", url, null, includeMetainfo = true, config))
    assertEquals(hash, export.infoHash)
    assertEquals("pack", export.name)
    assertNull(export.metainfo)
    val magnet = MagnetUri.parse(assertNotNull(export.magnet))
    assertEquals(listOf(tracker), magnet.trackers)
    assertTrue(magnet.explicitPeers.isEmpty())
    assertTrue(magnet.selectOnly.isEmpty())
    assertFalse("x.pe" in assertNotNull(export.magnet))
  }

  @Test
  fun `a link that is no magnet exports nothing until its metainfo is known`() {
    assertNull(
      exportOf("t1", "https://example.com/pack.torrent", null, includeMetainfo = true, config)
    )
  }
}
