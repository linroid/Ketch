package com.linroid.kdown.engine
import com.linroid.kdown.core.engine.ServerInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerInfoTest {

  @Test
  fun supportsResume_whenRangesAndContentLength() {
    val info = ServerInfo(
      contentLength = 1000,
      acceptRanges = true,
      etag = null,
      lastModified = null
    )
    assertTrue(info.supportsResume)
  }

  @Test
  fun doesNotSupportResume_whenNoRanges() {
    val info = ServerInfo(
      contentLength = 1000,
      acceptRanges = false,
      etag = null,
      lastModified = null
    )
    assertFalse(info.supportsResume)
  }

  @Test
  fun doesNotSupportResume_whenNoContentLength() {
    val info = ServerInfo(
      contentLength = null,
      acceptRanges = true,
      etag = null,
      lastModified = null
    )
    assertFalse(info.supportsResume)
  }

  @Test
  fun doesNotSupportResume_whenZeroContentLength() {
    val info = ServerInfo(
      contentLength = 0,
      acceptRanges = true,
      etag = null,
      lastModified = null
    )
    assertFalse(info.supportsResume)
  }
}
