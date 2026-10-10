package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ProxyConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.http.Url
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmTransportsTest {
  private val https = Url("https://example.com/file")
  private val http = Url("http://example.com/file")

  @Test
  fun environment_schemeVariable_winsOverAllProxy() {
    val proxies = EnvironmentProxies(
      mapOf(
        "https_proxy" to "http://secure.test:3128",
        "HTTP_PROXY" to "plain.test:8080",
        "ALL_PROXY" to "socks5://all.test",
      ),
    )
    assertEquals(ProxyRoute.Http("secure.test", 3128), proxies.routeFor(https))
    assertEquals(ProxyRoute.Http("plain.test", 8080), proxies.routeFor(http))
    assertEquals(
      ProxyRoute.Socks5("all.test", 1080),
      EnvironmentProxies(mapOf("all_proxy" to "socks5h://all.test")).routeFor(https),
    )
  }

  @Test
  fun environment_lowerCase_winsOverUpperCase() {
    val proxies = EnvironmentProxies(
      mapOf("https_proxy" to "lower.test:1", "HTTPS_PROXY" to "upper.test:2"),
    )
    assertEquals(ProxyRoute.Http("lower.test", 1), proxies.routeFor(https))
  }

  @Test
  fun environment_credentials_goToRouteNotAddress() {
    val proxies = EnvironmentProxies(mapOf("HTTPS_PROXY" to "http://me:s%40cret@proxy.test:3128"))
    assertEquals(
      ProxyRoute.Http("proxy.test", 3128, ProxyCredentials("me", "s@cret")),
      proxies.routeFor(https),
    )
  }

  @Test
  fun environment_noProxy_bypassesListedHostsAndSkipsInvalidEntries() {
    val proxies = EnvironmentProxies(
      mapOf(
        "https_proxy" to "proxy.test:3128",
        "NO_PROXY" to "bad entry/x, .example.com,10.0.0.0/8",
      ),
    )
    assertEquals(ProxyRoute.Direct, proxies.routeFor(https))
    assertEquals(ProxyRoute.Direct, proxies.routeFor(Url("https://10.1.2.3/file")))
    assertEquals(ProxyRoute.Http("proxy.test", 3128), proxies.routeFor(Url("https://other.test/")))
  }

  @Test
  fun environment_withoutProxyVariables_leavesItToTheSelector() {
    assertNull(EnvironmentProxies(mapOf("NO_PROXY" to "*")).routeFor(https))
  }

  @Test
  fun environment_unsupportedProxy_failsInsteadOfConnectingDirectly() {
    val proxies = EnvironmentProxies(mapOf("https_proxy" to "https://proxy.test"))
    assertIs<KetchError.Unsupported>(assertFailsWith<KetchError> { proxies.routeFor(https) })
  }

  @Test
  fun system_withoutEnvironment_usesSelectorProxy() {
    val transports = JvmTransports(null, emptyMap()) {
      FixedSelector(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("socks.test", 9050)))
    }
    try {
      assertEquals(
        ProxyRoute.Socks5("socks.test", 9050),
        transports.clientFor(https, ProxyConfig.System).route,
      )
    } finally {
      transports.close()
    }
  }

  @Test
  fun direct_usesCioOnlyWhenTheSelectorWouldToo() {
    val proxied = JvmTransports(null, emptyMap()) {
      FixedSelector(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("jvm.test", 3128)))
    }
    val direct = JvmTransports(null, emptyMap()) { FixedSelector(Proxy.NO_PROXY) }
    try {
      val forced = proxied.clientFor(https, ProxyConfig.Direct)
      assertEquals(ProxyRoute.Direct, forced.route)
      // CIO would ask the JVM's selector and take its proxy anyway.
      assertTrue(forced.client.engine.config !is CIOEngineConfig)
      assertIs<CIOEngineConfig>(direct.clientFor(https, ProxyConfig.System).client.engine.config)
    } finally {
      proxied.close()
      direct.close()
    }
  }

  @Test
  fun clientFor_sameRoute_reusesClient() {
    val transports = JvmTransports(null, emptyMap()) { FixedSelector(Proxy.NO_PROXY) }
    try {
      val proxy = ProxyConfig.manual("http://proxy.test:3128")
      val first = transports.clientFor(https, proxy).client
      assertTrue(first === transports.clientFor(Url("https://other.test/a"), proxy).client)
    } finally {
      transports.close()
    }
  }

  private class FixedSelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = listOf(proxy)

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {}
  }
}
