package com.linroid.ketch.api

import kotlin.test.Test
import kotlin.test.assertEquals

class NoDownloadSourceExceptionTest {

  @Test
  fun reason_noSourceTakesTheInput_saysSo() {
    val error = KetchError.Unsupported(NoDownloadSourceException())

    assertEquals("No download source handles this link", error.reason())
    val content = "No download source recognizes this content"
    assertEquals(content, error.reason(noSource = content))
  }

  @Test
  fun reason_sourceLacksSomething_saysWhat() {
    val error = KetchError.Unsupported(
      IllegalArgumentException("https_proxy is not an HTTP or SOCKS5 proxy URL"),
    )

    assertEquals("Not supported: https_proxy is not an HTTP or SOCKS5 proxy URL", error.reason())
  }

  @Test
  fun reason_causeWithoutAMessage_saysOnlyNotSupported() {
    assertEquals("Not supported", KetchError.Unsupported().reason())
    assertEquals("Not supported", KetchError.Unsupported(IllegalStateException(" ")).reason())
  }

  @Test
  fun reason_causeNamingALink_leavesItOut() {
    // A link may hold credentials, such as a proxy's.
    val causes = listOf(
      "socks4://user:secret@proxy.example:1080 is not supported",
      "magnet:?xt=urn:btih:abc has no trackers",
    )
    for (message in causes) {
      val error = KetchError.Unsupported(IllegalStateException(message))

      assertEquals("Not supported", error.reason())
    }
  }
}
