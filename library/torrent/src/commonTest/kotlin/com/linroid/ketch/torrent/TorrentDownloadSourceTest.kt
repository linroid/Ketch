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
  fun canHandleContent_torrentFileName_caseInsensitive() {
    assertTrue(source.canHandleContent("anything".encodeToByteArray(), "Ubuntu.TORRENT"))
  }

  @Test
  fun canHandleContent_bencodedDictionaryWithoutTorrentName() {
    val content = "d8:announce3:urle".encodeToByteArray()
    assertTrue(source.canHandleContent(content, null))
    assertTrue(source.canHandleContent(content, "download"))
  }

  @Test
  fun canHandleContent_otherContent_returnsFalse() {
    assertFalse(source.canHandleContent("hello".encodeToByteArray(), "notes.txt"))
    assertFalse(source.canHandleContent("data".encodeToByteArray(), null))
    assertFalse(source.canHandleContent("d".encodeToByteArray(), null))
    assertFalse(source.canHandleContent(ByteArray(0), null))
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
