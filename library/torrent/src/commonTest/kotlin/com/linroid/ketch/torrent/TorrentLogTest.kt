package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentLogTest {
  @Test
  fun trackerLabel_passkeyInPathOrQuery_isDropped() {
    assertEquals("https://tracker.example:443",
      trackerLabel("https://tracker.example/0123abcd/announce?passkey=secret"))
    assertEquals("udp://open.example:6969", trackerLabel("udp://open.example:6969/announce"))
  }

  @Test
  fun trackerLabel_userInfo_isDropped() {
    assertEquals("http://tracker.example:80", trackerLabel("http://user:pass@tracker.example/a"))
  }

  @Test
  fun describeWithoutUrls_embeddedRequestUrl_keepsOnlyHost() {
    val error = KetchError.Network(IllegalStateException(
      "Connect timeout has expired [url=https://tracker.example/announce?passkey=secret&" +
        "info_hash=%12, connect_timeout=unknown ms]"))
    val description = error.describeWithoutUrls()
    assertFalse("secret" in description)
    assertFalse("info_hash" in description)
    assertTrue("[url=https://tracker.example:443, connect_timeout=unknown ms]" in description)
  }
}
