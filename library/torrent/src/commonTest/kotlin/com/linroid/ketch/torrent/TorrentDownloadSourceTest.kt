package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentDownloadSourceTest {

  private val source = TorrentDownloadSource()

  @Test
  fun canHandle_magnetUri() {
    assertTrue(source.canHandle("magnet:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_magnetUri_caseInsensitive() {
    assertTrue(source.canHandle("MAGNET:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_torrentUrl() {
    assertTrue(
      source.canHandle("https://example.com/file.torrent"),
    )
  }

  @Test
  fun canHandle_torrentUrlWithQueryParams() {
    assertTrue(
      source.canHandle("https://example.com/file.torrent?key=val"),
    )
  }

  @Test
  fun canHandle_httpUrl_returnsFalse() {
    assertFalse(source.canHandle("https://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_returnsFalse() {
    assertFalse(source.canHandle("ftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_emptyString_returnsFalse() {
    assertFalse(source.canHandle(""))
  }

  @Test
  fun type_isTorrent() {
    kotlin.test.assertEquals("torrent", source.type)
  }

  @Test
  fun managesOwnFileIo_isTrue() {
    assertTrue(source.managesOwnFileIo)
  }
}
