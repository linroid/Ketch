package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {

  @Test
  fun `default config has expected values`() {
    val config = ServerConfig()
    assertEquals("0.0.0.0", config.host)
    assertEquals(8642, config.port)
    assertNull(config.apiToken)
    assertEquals(true, config.mdnsEnabled)
    assertEquals("_ketch._tcp", ServerConfig.MDNS_SERVICE_TYPE)
    assertEquals(emptyList(), config.corsAllowedHosts)
  }

  @Test
  fun `port 0 is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      ServerConfig(port = 0)
    }
  }

  @Test
  fun `port 65536 is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      ServerConfig(port = 65536)
    }
  }

  @Test
  fun `negative port is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      ServerConfig(port = -1)
    }
  }

  @Test
  fun `valid port range boundaries`() {
    ServerConfig(port = 1)
    ServerConfig(port = 65535)
  }

  @Test
  fun `custom config values`() {
    val config = ServerConfig(
      host = "127.0.0.1",
      port = 9000,
      apiToken = "my-token",
      mdnsEnabled = false,
      corsAllowedHosts = listOf("localhost:3000"),
    )
    assertEquals("127.0.0.1", config.host)
    assertEquals(9000, config.port)
    assertEquals("my-token", config.apiToken)
    assertEquals(false, config.mdnsEnabled)
    assertEquals(
      listOf("localhost:3000"), config.corsAllowedHosts,
    )
  }

  @Test
  fun `only loopback bind addresses count as this machine only`() {
    assertTrue(ServerConfig(host = ServerConfig.LOOPBACK_HOST).isLoopbackOnly)
    assertTrue(ServerConfig(host = "localhost").isLoopbackOnly)
    assertTrue(ServerConfig(host = "::1").isLoopbackOnly)
    assertFalse(ServerConfig().isLoopbackOnly)
    // A specific LAN address still accepts other devices.
    assertFalse(ServerConfig(host = "192.168.1.20").isLoopbackOnly)
  }

  @Test
  fun `configs written before auto start existed keep the server manual`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[server]
      |port = 9000
      """.trimMargin(),
    )
    assertFalse(decoded.server.autoStart)
    assertEquals(9000, decoded.server.port)
  }
}
