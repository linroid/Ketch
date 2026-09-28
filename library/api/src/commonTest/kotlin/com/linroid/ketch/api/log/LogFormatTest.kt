package com.linroid.ketch.api.log

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFormatTest {
  @Test
  fun describeCauses_nestedError_listsEachCause() {
    val error = KetchError.Network(IllegalStateException("Connect timeout"))
    assertEquals(
      "Network: Network error occurred <- IllegalStateException: Connect timeout",
      error.describeCauses(),
    )
  }

  @Test
  fun describeCauses_wrapperCopiesCauseMessage_omitsDuplicate() {
    val cause = IllegalArgumentException("bad input")
    val error = RuntimeException(cause)
    assertEquals(
      "RuntimeException <- IllegalArgumentException: bad input",
      error.describeCauses(),
    )
  }

  @Test
  fun describeCauses_multilineMessage_staysOnOneLine() {
    val description = IllegalStateException("first\nsecond").describeCauses()
    assertFalse('\n' in description)
  }

  @Test
  fun describeCauses_deepChain_isBounded() {
    var error: Throwable = IllegalStateException("root")
    repeat(20) { error = IllegalStateException("level $it", error) }
    assertEquals(8, error.describeCauses().split(" <- ").size)
  }

  @Test
  fun redactUrl_password_isMasked() {
    assertEquals(
      "ftp://user:***@example.com:2121/pub/file.iso",
      redactUrl("ftp://user:secret@example.com:2121/pub/file.iso"),
    )
  }

  @Test
  fun redactUrl_passwordContainingAt_isMaskedUpToHost() {
    assertEquals("ftp://user:***@example.com/", redactUrl("ftp://user:p@ss@example.com/"))
  }

  @Test
  fun redactUrl_passwordContainingQueryOrFragmentMark_isMasked() {
    assertEquals("ftp://user:***@example.com/file", redactUrl("ftp://user:pa?ss@example.com/file"))
    assertEquals("ftp://user:***@example.com/file", redactUrl("ftp://user:pa#ss@example.com/file"))
  }

  @Test
  fun redactUrl_withoutPassword_isUnchanged() {
    val urls = listOf(
      "https://example.com:8443/file.zip?name=a@b",
      "ftp://anonymous@example.com/file",
      "https://[::1]:8080/file",
      "/local/path/file.torrent",
    )
    urls.forEach { assertEquals(it, redactUrl(it)) }
  }

  @Test
  fun redactUrl_credentialQueryParameters_areMasked() {
    assertEquals(
      "https://tracker.example/download.php?id=42&passkey=***&name=a.torrent#top",
      redactUrl("https://tracker.example/download.php?id=42&passkey=0a1b2c&name=a.torrent#top"),
    )
    assertEquals(
      "https://bucket.example/f.iso?X-Amz-Credential=***&X-Amz-Signature=***&partNumber=1",
      redactUrl("https://bucket.example/f.iso?X-Amz-Credential=AKIA/2026&X-Amz-Signature=ff00" +
        "&partNumber=1"),
    )
  }

  @Test
  fun describeCauses_embeddedUrl_isRedacted() {
    val error = IllegalStateException("Timeout [url=https://host.example/f?token=abc, ms=10]")
    assertEquals(
      "IllegalStateException: Timeout [url=https://host.example/f?token=***, ms=10]",
      error.describeCauses(),
    )
  }

  @Test
  fun redactUrl_magnet_keepsTopicAndNameOnly() {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu" +
      "&tr=https%3A%2F%2Ftracker.example%2Fpasskey%2Fannounce&tr=udp%3A%2F%2Fopen.example%3A80"
    val redacted = redactUrl(magnet)
    assertEquals(
      "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu (+2 parameters)",
      redacted,
    )
    assertFalse("passkey" in redacted)
  }

  @Test
  fun formatLogLine_stackTraceQuotingUrls_isRedacted() {
    val cause = IllegalStateException("Timeout [url=https://host.example/f?token=s3cr3t]")
    val line = formatLogLine(LogLevel.ERROR, "[Tag] failed", RuntimeException("magnet:?xt=" +
      "urn:btih:0123456789abcdef0123456789abcdef01234567&tr=udp%3A%2F%2Fpasskey", cause))
    assertFalse("s3cr3t" in line)
    assertFalse("passkey" in line)
    assertTrue("https://host.example/f?token=***" in line)
  }

  @Test
  fun formatLogLine_throwable_appendsStackTrace() {
    val line = formatLogLine(LogLevel.WARN, "[Tag] failed", IllegalStateException("boom"))
    assertTrue(line.lines().first().endsWith("[WARN] [Tag] failed"))
    assertTrue("boom" in line.lines().drop(1).joinToString("\n"))
  }
}
