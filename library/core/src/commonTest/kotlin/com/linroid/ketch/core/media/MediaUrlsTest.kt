package com.linroid.ketch.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaUrlsTest {
  private val base = "https://example.com/media/index.m3u8?signature=a%2Fb"

  @Test
  fun mediaUrls_crossOriginReferences_cannotReceiveCredentials() {
    val headers = mapOf("Cookie" to "secret", "Authorization" to "Bearer secret",
      "Referer" to "https://example.com/private", "User-Agent" to "Ketch")
    val next = mediaUrl(base, "//cdn.example.com/a.ts?signature=1%2F2")
    assertEquals("https://cdn.example.com/a.ts?signature=1%2F2", next)
    assertEquals(mapOf("User-Agent" to "Ketch"), mediaHeaders(base, next, headers))
    assertEquals(headers, mediaHeaders(base, "https://example.com/other", headers))
    assertTrue(mediaUrl(base, "?signature=x").endsWith("index.m3u8?signature=x"))
  }
}
