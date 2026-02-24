package com.linroid.ketch.ftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtpUrlTest {

  @Test
  fun parse_simpleFtpUrl() {
    val url = FtpUrl.parse("ftp://example.com/file.zip")
    assertFalse(url.isTls)
    assertEquals("anonymous", url.username)
    assertEquals("", url.password)
    assertEquals("example.com", url.host)
    assertEquals(21, url.port)
    assertEquals("file.zip", url.path)
  }

  @Test
  fun parse_ftpsUrl_defaultPort() {
    val url = FtpUrl.parse("ftps://example.com/file.zip")
    assertTrue(url.isTls)
    assertEquals(990, url.port)
    assertEquals("example.com", url.host)
    assertEquals("file.zip", url.path)
  }

  @Test
  fun parse_withCredentials() {
    val url = FtpUrl.parse("ftp://user:secret@example.com/file.zip")
    assertEquals("user", url.username)
    assertEquals("secret", url.password)
    assertEquals("example.com", url.host)
    assertEquals("file.zip", url.path)
  }

  @Test
  fun parse_withUsernameOnly() {
    val url = FtpUrl.parse("ftp://admin@example.com/file.zip")
    assertEquals("admin", url.username)
    assertEquals("", url.password)
  }

  @Test
  fun parse_customPort() {
    val url = FtpUrl.parse("ftp://example.com:2121/file.zip")
    assertEquals(2121, url.port)
    assertEquals("example.com", url.host)
  }

  @Test
  fun parse_ftpsCustomPort() {
    val url = FtpUrl.parse("ftps://example.com:8990/file.zip")
    assertTrue(url.isTls)
    assertEquals(8990, url.port)
  }

  @Test
  fun parse_fullUrl() {
    val url = FtpUrl.parse(
      "ftps://user:pass@example.com:990/dir/file.zip"
    )
    assertTrue(url.isTls)
    assertEquals("user", url.username)
    assertEquals("pass", url.password)
    assertEquals("example.com", url.host)
    assertEquals(990, url.port)
    assertEquals("dir/file.zip", url.path)
  }

  @Test
  fun parse_deepPath() {
    val url = FtpUrl.parse(
      "ftp://example.com/pub/releases/v2/archive.tar.gz"
    )
    assertEquals("pub/releases/v2/archive.tar.gz", url.path)
  }

  @Test
  fun parse_noPath() {
    val url = FtpUrl.parse("ftp://example.com")
    assertEquals("", url.path)
  }

  @Test
  fun parse_emptyPath_trailingSlash() {
    val url = FtpUrl.parse("ftp://example.com/")
    assertEquals("", url.path)
  }

  @Test
  fun parse_percentEncodedPath() {
    val url = FtpUrl.parse("ftp://example.com/my%20file%20(1).zip")
    assertEquals("my file (1).zip", url.path)
  }

  @Test
  fun parse_percentEncodedCredentials() {
    val url = FtpUrl.parse(
      "ftp://user%40domain:p%40ss%3Aword@example.com/file.zip"
    )
    assertEquals("user@domain", url.username)
    assertEquals("p@ss:word", url.password)
  }

  @Test
  fun parse_caseInsensitiveScheme() {
    val url1 = FtpUrl.parse("FTP://example.com/file.zip")
    assertFalse(url1.isTls)

    val url2 = FtpUrl.parse("FTPS://example.com/file.zip")
    assertTrue(url2.isTls)

    val url3 = FtpUrl.parse("Ftp://example.com/file.zip")
    assertFalse(url3.isTls)
  }

  @Test
  fun parse_ipv6Host() {
    val url = FtpUrl.parse("ftp://[::1]:2121/file.zip")
    assertEquals("::1", url.host)
    assertEquals(2121, url.port)
  }

  @Test
  fun parse_ipv6Host_defaultPort() {
    val url = FtpUrl.parse("ftp://[::1]/file.zip")
    assertEquals("::1", url.host)
    assertEquals(21, url.port)
  }

  @Test
  fun parse_passwordWithAtSign() {
    // lastIndexOf('@') should handle '@' in password
    val url = FtpUrl.parse(
      "ftp://user:p%40ss@example.com/file.zip"
    )
    assertEquals("user", url.username)
    assertEquals("p@ss", url.password)
    assertEquals("example.com", url.host)
  }

  @Test
  fun parse_httpUrl_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("http://example.com/file.zip")
    }
  }

  @Test
  fun parse_httpsUrl_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("https://example.com/file.zip")
    }
  }

  @Test
  fun parse_sftpUrl_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("sftp://example.com/file.zip")
    }
  }

  @Test
  fun parse_noScheme_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("example.com/file.zip")
    }
  }

  @Test
  fun parse_invalidPort_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("ftp://example.com:abc/file.zip")
    }
  }

  @Test
  fun parse_emptyHost_throwsIllegalArgument() {
    assertFailsWith<IllegalArgumentException> {
      FtpUrl.parse("ftp:///file.zip")
    }
  }

  @Test
  fun parse_ipv4Host() {
    val url = FtpUrl.parse("ftp://192.168.1.100/file.zip")
    assertEquals("192.168.1.100", url.host)
    assertEquals(21, url.port)
  }

  @Test
  fun parse_ipv4Host_withPort() {
    val url = FtpUrl.parse("ftp://192.168.1.100:2121/file.zip")
    assertEquals("192.168.1.100", url.host)
    assertEquals(2121, url.port)
  }
}
