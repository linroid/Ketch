package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Additional edge case tests for [TorrentDownloadSource.canHandle]
 * and static properties.
 */
class TorrentDownloadSourceEdgeCaseTest {

  private val source = TorrentDownloadSource()

  // -- canHandle edge cases --

  @Test
  fun canHandle_magnetWithFragment() {
    assertTrue(
      source.canHandle("magnet:?xt=urn:btih:abc#fragment"),
    )
  }

  @Test
  fun canHandle_torrentUrlWithMultipleQueryParams() {
    assertTrue(
      source.canHandle(
        "https://example.com/file.torrent?a=1&b=2&c=3",
      ),
    )
  }

  @Test
  fun canHandle_torrentInMiddleOfPath_returnsFalse() {
    // ".torrent" is in the filename part but not at the end
    assertFalse(
      source.canHandle(
        "https://example.com/file.torrent.bak",
      ),
    )
  }

  @Test
  fun canHandle_httpUrlEndingInTorrent_noExtension() {
    // "torrent" at end without the dot
    assertFalse(
      source.canHandle("https://example.com/gettorrent"),
    )
  }

  @Test
  fun canHandle_justMagnetColon() {
    // "magnet:" followed by something
    assertTrue(source.canHandle("magnet:abc"))
  }

  @Test
  fun canHandle_magnetUppercase() {
    assertTrue(
      source.canHandle("MAGNET:?xt=urn:btih:hash"),
    )
  }

  @Test
  fun canHandle_torrentExtensionUppercase() {
    assertTrue(
      source.canHandle("https://example.com/FILE.TORRENT"),
    )
  }

  @Test
  fun canHandle_torrentExtensionMixedCase() {
    assertTrue(
      source.canHandle("https://example.com/file.Torrent"),
    )
  }

  @Test
  fun canHandle_ftpTorrentUrl() {
    // FTP URL pointing to a .torrent file
    assertTrue(
      source.canHandle("ftp://example.com/download/file.torrent"),
    )
  }

  @Test
  fun canHandle_plainHttpUrl_returnsFalse() {
    assertFalse(source.canHandle("http://example.com/file.mp4"))
  }

  @Test
  fun canHandle_dataUrl_returnsFalse() {
    assertFalse(source.canHandle("data:text/plain;base64,abc"))
  }

  @Test
  fun canHandle_blankString_returnsFalse() {
    assertFalse(source.canHandle("   "))
  }

  // -- Static properties --

  @Test
  fun type_constantValue() {
    assertEquals("torrent", TorrentDownloadSource.TYPE)
  }

  @Test
  fun managesOwnFileIo_alwaysTrue() {
    val s1 = TorrentDownloadSource()
    val s2 = TorrentDownloadSource(TorrentConfig())
    assertTrue(s1.managesOwnFileIo)
    assertTrue(s2.managesOwnFileIo)
  }
}
