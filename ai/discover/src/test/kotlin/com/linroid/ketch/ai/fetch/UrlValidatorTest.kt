package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UrlValidatorTest {

  private val validator = UrlValidator()

  // -- Valid URLs --

  @Test
  fun validate_httpUrl_returnsValid() = runTest {
    val result = validator.validate("http://example.com/file.iso")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrl_returnsValid() = runTest {
    val result = validator.validate("https://example.com/file.iso")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrlWithPort_returnsValid() = runTest {
    val result = validator.validate("https://example.com:8080/path")
    assertIs<ValidationResult.Valid>(result)
  }

  @Test
  fun validate_httpsUrlWithQuery_returnsValid() = runTest {
    val result = validator.validate(
      "https://example.com/download?id=123&type=iso"
    )
    assertIs<ValidationResult.Valid>(result)
  }

  // -- Blocked schemes --

  @Test
  fun validate_fileScheme_returnsBlocked() = runTest {
    val result = validator.validate("file:///etc/passwd")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_ftpScheme_returnsBlocked() = runTest {
    val result = validator.validate("ftp://example.com/file.iso")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_noScheme_returnsBlocked() = runTest {
    val result = validator.validate("example.com/file.iso")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_javascriptScheme_returnsBlocked() = runTest {
    val result = validator.validate("javascript:alert(1)")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Blocked IPs --

  @Test
  fun validate_localhost_returnsBlocked() = runTest {
    val result = validator.validate("http://localhost/admin")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_127001_returnsBlocked() = runTest {
    val result = validator.validate("http://127.0.0.1/admin")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_10Network_returnsBlocked() = runTest {
    val result = validator.validate("http://10.0.0.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_172_16Network_returnsBlocked() = runTest {
    val result = validator.validate("http://172.16.0.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_192_168Network_returnsBlocked() = runTest {
    val result = validator.validate("http://192.168.1.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_linkLocal_returnsBlocked() = runTest {
    val result = validator.validate("http://169.254.1.1/")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Internal hostnames --

  @Test
  fun validate_dotLocal_returnsBlocked() = runTest {
    val result = validator.validate("http://myserver.local/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_dotInternal_returnsBlocked() = runTest {
    val result = validator.validate("http://api.internal/")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_noDots_returnsBlocked() = runTest {
    val result = validator.validate("http://intranet/")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Malformed --

  @Test
  fun validate_emptyString_returnsBlocked() = runTest {
    val result = validator.validate("")
    assertIs<ValidationResult.Blocked>(result)
  }

  @Test
  fun validate_malformedUrl_returnsBlocked() = runTest {
    val result = validator.validate("not a url at all")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- IPv6 --

  @Test
  fun validate_ipv6Loopback_returnsBlocked() = runTest {
    val result = validator.validate("http://[::1]/")
    assertIs<ValidationResult.Blocked>(result)
  }

  // -- Threading --

  @Test
  fun validate_lookupOnCallingThreadRefused_returnsValid() = runTest {
    // Android throws NetworkOnMainThreadException for lookups on its main thread.
    val caller = Thread.currentThread()
    val lookup = fakeDns("example.com" to "93.184.215.14")
    val validator = UrlValidator(resolve = { host ->
      check(Thread.currentThread() != caller) { "Looked up $host on the calling thread" }
      lookup(host)
    })

    assertIs<ValidationResult.Valid>(validator.validate("https://example.com/file.iso"))
  }

  // -- Checking without a lookup --

  @Test
  fun check_neverLooksUpTheHost() {
    val lookups = mutableListOf<String>()
    val validator = UrlValidator(resolve = { host ->
      lookups += host
      fakeDns("10.example" to "10.0.0.1")(host)
    })

    assertIs<ValidationResult.Valid>(validator.check("https://words.attacker.example/x"))
    // A name that resolves to a private address passes; only validate() can tell.
    assertIs<ValidationResult.Valid>(validator.check("https://10.example/"))
    assertIs<ValidationResult.Blocked>(validator.check("http://localhost/"))
    assertEquals(emptyList(), lookups)
  }

  @Test
  fun check_privateIpLiterals_areBlocked() {
    assertIs<ValidationResult.Blocked>(validator.check("http://192.168.1.1/"))
    assertIs<ValidationResult.Blocked>(validator.check("http://100.64.0.1/"))
    assertIs<ValidationResult.Blocked>(validator.check("http://[::ffff:127.0.0.1]/"))
    assertIs<ValidationResult.Valid>(validator.check("http://93.184.215.14/"))
  }

  @Test
  fun validate_ipLiteral_isNotLookedUp() = runTest {
    val lookups = mutableListOf<String>()
    val validator = UrlValidator(resolve = { host ->
      lookups += host
      throw UnknownHostException(host)
    })

    assertIs<ValidationResult.Valid>(validator.validate("http://93.184.215.14/file.iso"))
    assertEquals(emptyList(), lookups)
  }
}
