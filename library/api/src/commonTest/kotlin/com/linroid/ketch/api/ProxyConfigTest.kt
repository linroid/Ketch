package com.linroid.ketch.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyConfigTest {

  @Test
  fun parse_httpWithoutScheme_defaultsToHttp() {
    val address = ProxyAddress.parse("127.0.0.1:7890")
    assertEquals(ProxyAddress(ProxyAddress.Type.HTTP, "127.0.0.1", 7890), address)
  }

  @Test
  fun parse_withoutPort_usesDefaultPortOfScheme() {
    assertEquals(80, ProxyAddress.parse("http://proxy.lan")?.port)
    assertEquals(1080, ProxyAddress.parse("socks5://proxy.lan")?.port)
    assertEquals(1080, ProxyAddress.parse("socks5h://proxy.lan/")?.port)
  }

  @Test
  fun parse_bracketedIpv6_dropsBrackets() {
    val address = ProxyAddress.parse("socks5://[::1]:1080")
    assertEquals("::1", address?.host)
    assertEquals("socks5://[::1]:1080", address?.copy(username = null).toString())
  }

  @Test
  fun parse_percentEncodedCredentials_decodes() {
    val address = ProxyAddress.parse("http://me%40home:p%3Ass@proxy:3128")
    assertEquals("me@home", address?.username)
    assertEquals("p:ss", address?.password)
  }

  @Test
  fun parse_unsupportedOrMalformed_returnsNull() {
    for (url in listOf(
      "https://proxy:443", "socks4://proxy:1080", "http://proxy:3128/path", "http://proxy:0",
      "http://proxy:70000", "http://:8080", "http://pro xy:8080", "http://::1:8080",
      "http://proxy:+80", "http://%zz@proxy:8080", "", "ftp://proxy",
    )) {
      assertNull(ProxyAddress.parse(url), url)
    }
  }

  @Test
  fun toString_masksPassword() {
    val config = ProxyConfig.manual("socks5://me:secret@proxy:1080")
    assertFalse("secret" in config.toString())
    assertFalse("secret" in config.address.toString())
    assertFalse("secret" in DownloadRequest("https://a.test/f", proxy = config).toString())
  }

  @Test
  fun manual_movesCredentialsOutOfUrl() {
    val config = ProxyConfig.manual("socks5h://me:secret@Proxy.Lan:1080")
    assertEquals("socks5://proxy.lan:1080", config.url)
    assertEquals("me", config.username)
    assertEquals("secret", config.password)
    assertEquals(ProxyMode.MANUAL, config.mode)
  }

  @Test
  fun init_manualWithoutUrl_throws() {
    assertFailsWith<IllegalArgumentException> { ProxyConfig(mode = ProxyMode.MANUAL) }
  }

  @Test
  fun init_credentialsInUrl_throws() {
    assertFailsWith<IllegalArgumentException> {
      ProxyConfig(mode = ProxyMode.MANUAL, url = "http://me:secret@proxy:8080")
    }
  }

  @Test
  fun init_passwordWithoutUsername_throws() {
    assertFailsWith<IllegalArgumentException> {
      ProxyConfig(url = "http://proxy:8080", password = "secret")
    }
  }

  @Test
  fun init_invalidUrlInAnotherMode_throws() {
    // The URL is kept while another mode is chosen, so it must stay usable.
    assertFailsWith<IllegalArgumentException> { ProxyConfig(url = "https://proxy") }
  }

  @Test
  fun init_invalidBypassEntry_throws() {
    for (entry in listOf("", "a b", "a,b", "10.0.0.0/33", "*foo.com", "a.*.com", "host:port")) {
      assertFailsWith<IllegalArgumentException>(entry) {
        ProxyConfig(bypass = listOf(entry))
      }
    }
  }

  @Test
  fun bypasses_domainEntry_matchesDomainAndSubdomains() {
    for (entry in listOf("example.com", ".example.com", "*.example.com", "Example.com:8080")) {
      val config = ProxyConfig(bypass = listOf(entry))
      assertTrue(config.bypasses("example.com"), entry)
      assertTrue(config.bypasses("cdn.EXAMPLE.com"), entry)
      assertFalse(config.bypasses("notexample.com"), entry)
      assertFalse(config.bypasses("example.com.evil.test"), entry)
    }
  }

  @Test
  fun bypasses_cidrRange_matchesAddressesOnly() {
    val config = ProxyConfig(bypass = listOf("10.0.0.0/8", "fd00::/8", "192.168.1.7"))
    assertTrue(config.bypasses("10.20.30.40"))
    assertFalse(config.bypasses("11.0.0.1"))
    assertTrue(config.bypasses("[fd12::1]"))
    assertFalse(config.bypasses("fe80::1"))
    assertTrue(config.bypasses("192.168.1.7"))
    assertFalse(config.bypasses("192.168.1.8"))
    // Host names are never resolved.
    assertFalse(config.bypasses("ten.example"))
  }

  @Test
  fun bypasses_localEntry_matchesNamesWithoutDots() {
    val config = ProxyConfig(bypass = listOf("<local>"))
    assertTrue(config.bypasses("intranet"))
    assertFalse(config.bypasses("intranet.corp"))
    assertFalse(config.bypasses("10.0.0.1"))
  }

  @Test
  fun bypasses_star_matchesEverything() {
    assertTrue(ProxyConfig(bypass = listOf("*")).bypasses("example.com"))
  }

  @Test
  fun bypasses_loopback_alwaysTrue() {
    for (host in listOf("localhost", "app.localhost", "127.0.0.1", "127.8.9.10", "::1", "[::1]",
      "::ffff:127.0.0.1")) {
      assertTrue(ProxyConfig.System.bypasses(host), host)
    }
    assertFalse(ProxyConfig.System.bypasses("128.0.0.1"))
    assertFalse(ProxyConfig.System.bypasses("::2"))
  }

  @Test
  fun deserialize_downloadConfigWithoutProxy_followsSystem() {
    val config = Json.decodeFromString<DownloadConfig>("{}")
    assertEquals(ProxyConfig.System, config.proxy)
  }

  @Test
  fun deserialize_requestWithoutProxy_followsGlobalSetting() {
    val request = Json.decodeFromString<DownloadRequest>("""{"url":"https://a.test/f"}""")
    assertNull(request.proxy)
  }

  @Test
  fun serialize_mode_usesLowercaseName() {
    val json = Json.encodeToString(ProxyConfig.Direct)
    assertEquals("""{"mode":"direct"}""", json)
  }
}
