package com.linroid.kdown.app.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscoveredServerTest {

  @Test
  fun `construction assigns all fields`() {
    val server = DiscoveredServer(
      name = "My KDown",
      host = "192.168.1.10",
      port = 8642,
      tokenRequired = true,
    )
    assertEquals("My KDown", server.name)
    assertEquals("192.168.1.10", server.host)
    assertEquals(8642, server.port)
    assertTrue(server.tokenRequired)
  }

  @Test
  fun `tokenRequired false`() {
    val server = DiscoveredServer(
      name = "Open Server",
      host = "10.0.0.1",
      port = 9000,
      tokenRequired = false,
    )
    assertFalse(server.tokenRequired)
  }

  @Test
  fun `equality for same values`() {
    val a = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val b = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `inequality when name differs`() {
    val a = DiscoveredServer(
      name = "Server A",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val b = a.copy(name = "Server B")
    assertNotEquals(a, b)
  }

  @Test
  fun `inequality when host differs`() {
    val a = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val b = a.copy(host = "192.168.1.6")
    assertNotEquals(a, b)
  }

  @Test
  fun `inequality when port differs`() {
    val a = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val b = a.copy(port = 9000)
    assertNotEquals(a, b)
  }

  @Test
  fun `inequality when tokenRequired differs`() {
    val a = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val b = a.copy(tokenRequired = true)
    assertNotEquals(a, b)
  }

  @Test
  fun `copy preserves unchanged fields`() {
    val original = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = true,
    )
    val copied = original.copy(port = 9999)
    assertEquals("KDown", copied.name)
    assertEquals("192.168.1.5", copied.host)
    assertEquals(9999, copied.port)
    assertTrue(copied.tokenRequired)
  }
}