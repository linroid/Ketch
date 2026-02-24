package com.linroid.ketch.ai.fetch

import kotlin.test.Test
import kotlin.test.assertIs

class UrlValidatorTest {

  private val validator = UrlValidator()

  // -- Valid URLs --

  @Test
  fun validate_httpUrl_returnsValid() {
    val result = validator.validate("http://example.com/file.iso")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrl_returnsValid() {
    val result = validator.validate("https://example.com/file.iso")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrlWithPort_returnsValid() {
    val result = validator.validate("https://example.com:8080/path")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrlWithQuery_returnsValid() {
    val result = validator.validate(
      "https://example.com/download?id=123&type=iso"
    )
    assertIs<ValidationResult.Valid>(result)
  }

  // -- Blocked schemes --

  @Test
  fun validate_fileScheme_returnsBlocked() {
    val result = validator.validate("file:///etc/passwd")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_ftpScheme_returnsBlocked() {
    val result = validator.validate("ftp://example.com/file.iso")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_noScheme_returnsBlocked() {
    val result = validator.validate("example.com/file.iso")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_javascriptScheme_returnsBlocked() {
    val result = validator.validate("javascript:alert(1)")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Blocked IPs --

  @Test
  fun validate_localhost_returnsBlocked() {
    val result = validator.validate("http://localhost/admin")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_127001_returnsBlocked() {
    val result = validator.validate("http://127.0.0.1/admin")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_10Network_returnsBlocked() {
    val result = validator.validate("http://10.0.0.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_172_16Network_returnsBlocked() {
    val result = validator.validate("http://172.16.0.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_192_168Network_returnsBlocked() {
    val result = validator.validate("http://192.168.1.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_linkLocal_returnsBlocked() {
    val result = validator.validate("http://169.254.1.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Internal hostnames --

  @Test
  fun validate_dotLocal_returnsBlocked() {
    val result = validator.validate("http://myserver.local/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_dotInternal_returnsBlocked() {
    val result = validator.validate("http://api.internal/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_noDots_returnsBlocked() {
    val result = validator.validate("http://intranet/")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Malformed --

  @Test
  fun validate_emptyString_returnsBlocked() {
    val result = validator.validate("")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_malformedUrl_returnsBlocked() {
    val result = validator.validate("not a url at all")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- IPv6 --

  @Test
  fun validate_ipv6Loopback_returnsBlocked() {
    val result = validator.validate("http://[::1]/")
    assertIs<ValidationResult.Blocked>(result)
  }
}
