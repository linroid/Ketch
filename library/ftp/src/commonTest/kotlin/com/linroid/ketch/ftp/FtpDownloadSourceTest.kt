package com.linroid.ketch.ftp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtpDownloadSourceTest {

  private val source = FtpDownloadSource()

  @Test
  fun canHandle_ftpUrl() {
    assertTrue(source.canHandle("ftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpsUrl() {
    assertTrue(source.canHandle("ftps://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_caseInsensitive() {
    assertTrue(source.canHandle("FTP://example.com/file.zip"))
    assertTrue(source.canHandle("FTPS://example.com/file.zip"))
    assertTrue(source.canHandle("Ftp://example.com/file.zip"))
    assertTrue(source.canHandle("Ftps://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_withCredentials() {
    assertTrue(
      source.canHandle("ftp://user:pass@example.com/file.zip")
    )
  }

  @Test
  fun canHandle_ftpUrl_withPort() {
    assertTrue(source.canHandle("ftp://example.com:2121/file.zip"))
  }

  @Test
  fun canHandle_httpUrl_returnsFalse() {
    assertFalse(source.canHandle("http://example.com/file.zip"))
  }

  @Test
  fun canHandle_httpsUrl_returnsFalse() {
    assertFalse(source.canHandle("https://example.com/file.zip"))
  }

  @Test
  fun canHandle_sftpUrl_returnsFalse() {
    assertFalse(source.canHandle("sftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_magnetUrl_returnsFalse() {
    assertFalse(source.canHandle("magnet:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_localPath_returnsFalse() {
    assertFalse(source.canHandle("/local/path/file.zip"))
  }

  @Test
  fun canHandle_emptyString_returnsFalse() {
    assertFalse(source.canHandle(""))
  }
}
