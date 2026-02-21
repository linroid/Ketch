package com.linroid.kdown.server

import com.linroid.kdown.api.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KDownServerConfigTest {

  @Test
  fun `default config has expected values`() {
    val config = ServerConfig()
    assertEquals("0.0.0.0", config.host)
    assertEquals(8642, config.port)
    assertNull(config.apiToken)
    assertEquals(true, config.mdnsEnabled)
    assertEquals("_kdown._tcp", ServerConfig.MDNS_SERVICE_TYPE)
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
}
