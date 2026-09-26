package com.linroid.ketch.api.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LoggableUrlTest {

  @Test
  fun loggableUrl_regularUrl_unchanged() {
    val url = "https://example.com/file.torrent?passkey=abc"
    assertEquals(url, loggableUrl(url))
  }

  @Test
  fun loggableUrl_dataUrl_keepsHeaderAndPayloadLength() {
    assertEquals(
      "data:application/x-bittorrent;base64,<8 chars>",
      loggableUrl("data:application/x-bittorrent;base64,ZDQ6aW5m"),
    )
  }

  @Test
  fun loggableUrl_dataUrlWithoutComma_boundsHeader() {
    val url = "data:" + "x".repeat(1000)
    assertEquals(url.take(64) + ",<0 chars>", loggableUrl(url))
  }
}
