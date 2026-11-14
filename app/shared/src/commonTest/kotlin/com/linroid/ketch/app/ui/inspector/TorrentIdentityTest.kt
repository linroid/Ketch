package com.linroid.ketch.app.ui.inspector

import com.linroid.ketch.torrent.api.TorrentExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentIdentityTest {
  private val v1 = "a".repeat(40)
  private val v2 = "b".repeat(64)
  private val magnet = "magnet:?xt=urn:btih:$v1"

  @Test
  fun torrentIdentity_torrentFile_showsItsHashAndMagnet() {
    val export = TorrentExport("t1", infoHash = v1, infoHashV2 = v2, magnet = magnet)

    assertEquals(
      TorrentIdentityView(v1, magnet),
      torrentIdentity(export, "https://example.com/pack.torrent"),
    )
  }

  @Test
  fun torrentIdentity_v2Only_showsTheV2Hash() {
    val export = TorrentExport("t1", infoHashV2 = v2, magnet = "magnet:?xt=urn:btmh:1220$v2")

    assertEquals(v2, torrentIdentity(export, "torrent:$v2")?.infoHash)
  }

  @Test
  fun torrentIdentity_magnetTask_leavesTheMagnetToTheLink() {
    val export = TorrentExport("t1", infoHash = v1, magnet = magnet)

    assertEquals(TorrentIdentityView(v1, null), torrentIdentity(export, "MAGNET:?xt=urn:btih:$v1"))
  }

  @Test
  fun torrentIdentity_privateTorrent_hasNoMagnet() {
    val export = TorrentExport("t1", infoHash = v1, privateTorrent = true)

    assertEquals(TorrentIdentityView(v1, null), torrentIdentity(export, "torrent:$v1"))
  }

  @Test
  fun torrentIdentity_unknownTorrent_showsNothing() {
    assertNull(torrentIdentity(null, "https://example.com/pack.torrent"))
    assertNull(torrentIdentity(TorrentExport("t1"), "https://example.com/pack.torrent"))
  }
}
