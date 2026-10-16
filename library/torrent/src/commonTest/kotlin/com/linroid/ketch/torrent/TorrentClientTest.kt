package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentClientTest {
  @Test
  fun peerIdPrefixEncodesTheVersionAzureusStyle() {
    assertEquals("-KE0910-", TorrentClient.peerIdPrefix("0.9.1"))
    assertEquals("-KE1C30-", TorrentClient.peerIdPrefix("1.12.3"))
    assertEquals("-KE2000-", TorrentClient.peerIdPrefix("2.0"))
    // Pre-releases and builds that name no version are marked apart from releases.
    assertEquals("-KE0A0Z-", TorrentClient.peerIdPrefix("0.10.0-rc1"))
    assertEquals("-KE000Z-", TorrentClient.peerIdPrefix("dev"))
    // Numbers past what one character holds stay at its largest.
    assertEquals("-KEzzz0-", TorrentClient.peerIdPrefix("62.80.99999999999"))
  }

  @Test
  fun peerIdsStartWithThePrefixAndEndInRandomLettersAndDigits() {
    val prefix = TorrentClient.peerIdPrefix(KetchApi.VERSION)
    assertEquals(prefix, TorrentClient.peerIdPrefix)
    val ids = List(32) { TorrentClient.peerId() }
    for (id in ids) {
      assertEquals(20, id.size)
      val text = id.decodeToString()
      assertTrue(text.startsWith(prefix))
      assertTrue(text.substring(8).all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' })
    }
    // Twelve random characters: no two engines share an ID.
    assertEquals(ids.size, ids.map { it.decodeToString() }.distinct().size)
  }

  @Test
  fun namesKetchWithItsVersion() {
    assertEquals("Ketch ${KetchApi.VERSION}", TorrentClient.name)
    assertEquals("Ketch/${KetchApi.VERSION}", TorrentClient.userAgent)
  }
}
