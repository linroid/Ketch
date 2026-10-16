package com.linroid.ketch.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyTest {

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
    val proxy = Proxy.Manual("socks5://me:secret@proxy:1080")
    assertFalse("secret" in proxy.toString())
    assertFalse("secret" in proxy.address.toString())
    assertFalse("secret" in DownloadRequest("https://a.test/f", proxy = proxy).toString())
  }

  @Test
  fun manual_fromUrl_keepsCredentialsInTheAddress() {
    val proxy = Proxy.Manual("socks5h://me:secret@Proxy.Lan:1080")
    assertEquals("socks5://proxy.lan:1080", proxy.address.url)
    assertEquals("me", proxy.address.username)
    assertEquals("secret", proxy.address.password)
  }

  @Test
  fun manual_invalidUrl_throws() {
    assertFailsWith<IllegalArgumentException> { Proxy.Manual("https://proxy") }
  }

  @Test
  fun address_passwordWithoutUsername_throws() {
    assertFailsWith<IllegalArgumentException> {
      ProxyAddress(ProxyAddress.Type.HTTP, "proxy", 8080, password = "secret")
    }
  }

  @Test
  fun manual_invalidBypassEntry_throws() {
    for (entry in listOf("", "a b", "a,b", "10.0.0.0/33", "*foo.com", "a.*.com", "host:port")) {
      assertFailsWith<IllegalArgumentException>(entry) { manual(entry) }
    }
  }

  @Test
  fun bypasses_domainEntry_matchesDomainAndSubdomains() {
    for (entry in listOf("example.com", ".example.com", "*.example.com", "Example.com:8080")) {
      val proxy = manual(entry)
      assertTrue(proxy.bypasses("example.com"), entry)
      assertTrue(proxy.bypasses("cdn.EXAMPLE.com"), entry)
      assertFalse(proxy.bypasses("notexample.com"), entry)
      assertFalse(proxy.bypasses("example.com.evil.test"), entry)
    }
  }

  @Test
  fun bypasses_cidrRange_matchesAddressesOnly() {
    val proxy = manual("10.0.0.0/8", "fd00::/8", "192.168.1.7")
    assertTrue(proxy.bypasses("10.20.30.40"))
    assertFalse(proxy.bypasses("11.0.0.1"))
    assertTrue(proxy.bypasses("[fd12::1]"))
    assertFalse(proxy.bypasses("fe80::1"))
    assertTrue(proxy.bypasses("192.168.1.7"))
    assertFalse(proxy.bypasses("192.168.1.8"))
    // Host names are never resolved.
    assertFalse(proxy.bypasses("ten.example"))
  }

  @Test
  fun bypasses_localEntry_matchesNamesWithoutDots() {
    val proxy = manual("<local>")
    assertTrue(proxy.bypasses("intranet"))
    assertFalse(proxy.bypasses("intranet.corp"))
    assertFalse(proxy.bypasses("10.0.0.1"))
  }

  @Test
  fun bypasses_star_matchesEverything() {
    assertTrue(manual("*").bypasses("example.com"))
  }

  @Test
  fun bypasses_loopback_alwaysTrue() {
    for (host in listOf("localhost", "app.localhost", "127.0.0.1", "127.8.9.10", "::1", "[::1]",
      "::ffff:127.0.0.1")) {
      assertTrue(manual().bypasses(host), host)
    }
    assertFalse(manual().bypasses("128.0.0.1"))
    assertFalse(manual().bypasses("::2"))
  }

  @Test
  fun deserialize_downloadConfigWithoutProxy_followsSystem() {
    val config = Json.decodeFromString<DownloadConfig>("{}")
    assertEquals(Proxy.System, config.proxy)
  }

  @Test
  fun deserialize_requestWithoutProxy_followsGlobalSetting() {
    val request = Json.decodeFromString<DownloadRequest>("""{"url":"https://a.test/f"}""")
    assertNull(request.proxy)
  }

  @Test
  fun serialize_writesOneFlatObject() {
    assertEquals("""{"mode":"direct"}""", Json.encodeToString<Proxy>(Proxy.Direct))
    val manual = Proxy.Manual("socks5://me:secret@proxy.lan:1080", listOf("*.lan"))
    assertEquals(
      """{"mode":"manual","url":"socks5://proxy.lan:1080","username":"me",""" +
        """"password":"secret","bypass":["*.lan"]}""",
      Json.encodeToString<Proxy>(manual),
    )
    assertEquals(manual, Json.decodeFromString<Proxy>(Json.encodeToString<Proxy>(manual)))
  }

  @Test
  fun deserialize_olderFormat_readsTheMode() {
    // Older versions kept the manual proxy's fields in the other modes.
    val kept = """{"mode":"direct","url":"http://proxy.lan:3128","username":"me"}"""
    assertEquals(Proxy.Direct, Json.decodeFromString<Proxy>(kept))
    assertEquals(Proxy.System, Json.decodeFromString<Proxy>("""{"mode":"system"}"""))
  }

  @Test
  fun deserialize_invalidManualProxy_throws() {
    for (json in listOf(
      """{"mode":"manual"}""",
      """{"mode":"manual","url":"https://proxy"}""",
      """{"mode":"manual","url":"http://me:secret@proxy:8080"}""",
      """{"mode":"manual","url":"http://proxy:8080","password":"secret"}""",
      """{"mode":"manual","url":"http://proxy:8080","bypass":["a b"]}""",
      """{"mode":"pac"}""",
    )) {
      assertFailsWith<SerializationException>(json) { Json.decodeFromString<Proxy>(json) }
    }
  }

  private fun manual(vararg bypass: String) = Proxy.Manual("http://proxy:8080", bypass.toList())
}
