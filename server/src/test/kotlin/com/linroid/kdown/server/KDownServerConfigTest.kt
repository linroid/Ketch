package com.linroid.kdown.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KDownServerConfigTest {

  @Test
  fun `default config has expected values`() {
    val config = KDownServerConfig.Default
    assertEquals("0.0.0.0", config.host)
    assertEquals(8642, config.port)
    assertNull(config.apiToken)
    assertEquals(emptyList(), config.corsAllowedHosts)
  }

  @Test
  fun `port 0 is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      KDownServerConfig(port = 0)
    }
  }

  @Test
  fun `port 65536 is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      KDownServerConfig(port = 65536)
    }
  }

  @Test
  fun `negative port is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      KDownServerConfig(port = -1)
    }
  }

  @Test
  fun `valid port range boundaries`() {
    KDownServerConfig(port = 1)
    KDownServerConfig(port = 65535)
  }

  @Test
  fun `custom config values`() {
    val config = KDownServerConfig(
      host = "127.0.0.1",
      port = 9000,
      apiToken = "my-token",
      corsAllowedHosts = listOf("localhost:3000")
    )
    assertEquals("127.0.0.1", config.host)
    assertEquals(9000, config.port)
    assertEquals("my-token", config.apiToken)
    assertEquals(
      listOf("localhost:3000"), config.corsAllowedHosts
    )
  }
}
