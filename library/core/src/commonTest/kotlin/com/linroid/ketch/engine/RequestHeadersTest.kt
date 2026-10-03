package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.RequestHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RequestHeadersTest {
  @Test
  fun requireValid_controlCharacterInValue_isRejectedWithoutQuotingValue() {
    for (value in listOf("sid=1\r\nX-Injected: 1", "sid=1\nX", "sid=1\u0000")) {
      val error = assertFailsWith<IllegalArgumentException> {
        RequestHeaders.requireValid(mapOf("Cookie" to value))
      }
      assertFalse("sid" in error.message.orEmpty(), error.message)
    }
  }

  @Test
  fun requireValid_tabAndNonAsciiInValue_areAccepted() {
    val headers = mapOf("X-Note" to "a\tb", "Referer" to "https://例え.jp/ページ")

    RequestHeaders.requireValid(headers)
  }

  @Test
  fun requireValid_nameWithSeparatorOrSpace_isRejectedWithoutQuotingValue() {
    for (name in listOf("Cookie: sid=1", "X Custom", "", "X-Name\r\n")) {
      val error = assertFailsWith<IllegalArgumentException>(name) {
        RequestHeaders.requireValid(mapOf(name to "value"))
      }
      assertFalse("sid=1" in error.message.orEmpty(), error.message)
    }
  }

  @Test
  fun sendable_engineManagedHeaders_areDroppedIgnoringCase() {
    val headers = mapOf(
      "host" to "evil.example",
      "Range" to "bytes=0-",
      "Content-Length" to "0",
      "Connection" to "close",
      "Transfer-Encoding" to "chunked",
      "Cookie" to "sid=1",
      "User-Agent" to "Browser/1.0",
    )

    assertEquals(
      mapOf("Cookie" to "sid=1", "User-Agent" to "Browser/1.0"),
      RequestHeaders.sendable(headers),
    )
  }
}
