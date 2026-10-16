package com.linroid.ketch.engine

import com.linroid.ketch.api.Proxy
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

class ProxyRouteTest {
  private val system = ProxyRoute.Http("system.test", 8080)

  @Test
  fun routeFor_manualProxy_carriesCredentialsToProxy() {
    val config = Proxy.Manual("socks5://me:secret@proxy.test:1080")
    assertEquals(
      ProxyRoute.Socks5("proxy.test", 1080, ProxyCredentials("me", "secret")),
      config.routeFor(Url("https://example.com/file")) { fail("not the system's") },
    )
  }

  @Test
  fun routeFor_manualBypass_goesDirectly() {
    val config = Proxy.Manual("http://proxy.test:3128", bypass = listOf("*.lan"))
    assertEquals(ProxyRoute.Direct, config.routeFor(Url("http://nas.lan/file")) { system })
    assertEquals(
      ProxyRoute.Http("proxy.test", 3128),
      config.routeFor(Url("http://nas.example/file")) { system },
    )
  }

  @Test
  fun routeFor_loopback_goesDirectlyWithoutAskingTheSystem() {
    for (url in listOf("http://localhost:8080/a", "http://127.0.0.1/a", "http://[::1]:80/a")) {
      assertEquals(ProxyRoute.Direct, Proxy.System.routeFor(Url(url)) { system }, url)
      val manual = Proxy.Manual("http://proxy.test:3128")
      assertEquals(ProxyRoute.Direct, manual.routeFor(Url(url)) { system }, url)
    }
  }

  @Test
  fun routeFor_system_asksTheSystem() {
    assertEquals(system, Proxy.System.routeFor(Url("https://example.com/file")) { system })
  }

  @Test
  fun routeFor_direct_ignoresSystem() {
    assertEquals(
      ProxyRoute.Direct,
      Proxy.Direct.routeFor(Url("https://example.com/file")) { system },
    )
  }

  @Test
  fun toString_leavesPasswordOut() {
    val route = ProxyRoute.Http("proxy.test", 3128, ProxyCredentials("me", "secret"))
    assertFalse("secret" in route.toString())
    assertFalse("secret" in route.credentials.toString())
  }
}
