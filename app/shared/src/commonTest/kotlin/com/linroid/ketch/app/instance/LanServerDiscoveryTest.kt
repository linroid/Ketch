package com.linroid.ketch.app.instance

import com.linroid.ketch.config.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMdnsDiscoverer(
  private val results: List<DiscoveredServer> = emptyList(),
) : MdnsDiscoverer {
  var lastServiceType: String? = null
    private set
  var discoverCallCount = 0
    private set

  override suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer> {
    discoverCallCount++
    lastServiceType = serviceType
    return results
  }
}

class LanServerDiscoveryTest {

  private fun server(name: String, host: String, port: Int = 8642, tokenRequired: Boolean = false) =
    DiscoveredServer(name, host, port, tokenRequired)

  private suspend fun discover(vararg servers: DiscoveredServer): List<DiscoveredServer> =
    LanServerDiscovery(FakeMdnsDiscoverer(servers.toList())).discover()

  @Test
  fun preferredAddress_linkLocalIpv6First_picksTheIpv4Address() {
    assertEquals("192.168.1.20", preferredAddress(listOf("fe80::1", "192.168.1.20")))
    assertEquals("fd00::20", preferredAddress(listOf("fe80::1", "fd00::20")))
    assertEquals("fe80::1", preferredAddress(listOf("fe80::1")))
    assertNull(preferredAddress(emptyList()))
  }

  @Test
  fun supported_discovererThatCanBrowse_isTrue() {
    assertTrue(LanServerDiscovery(FakeMdnsDiscoverer()).supported)
  }

  @Test
  fun supported_noOpDiscoverer_isFalse() {
    assertFalse(LanServerDiscovery(NoOpMdnsDiscoverer).supported)
  }

  @Test
  fun `empty discovery returns empty list`() = runTest {
    val fake = FakeMdnsDiscoverer()
    val result = LanServerDiscovery(fake).discover()
    assertTrue(result.isEmpty())
    assertEquals(1, fake.discoverCallCount)
  }

  @Test
  fun `passes correct service type`() = runTest {
    val fake = FakeMdnsDiscoverer()
    LanServerDiscovery(fake).discover()
    assertEquals(ServerConfig.MDNS_SERVICE_TYPE, fake.lastServiceType)
  }

  @Test
  fun `single server passes through`() = runTest {
    val server = server("Ketch", "192.168.1.5")
    assertEquals(listOf(server), discover(server))
  }

  @Test
  fun `different ports on same host are not deduplicated`() = runTest {
    val result = discover(server("Ketch A", "192.168.1.5"), server("Ketch B", "192.168.1.5", 9000))
    assertEquals(2, result.size)
  }

  @Test
  fun `results are sorted by host`() = runTest {
    val result = discover(
      server("C", "192.168.1.30"),
      server("A", "192.168.1.10"),
      server("B", "192.168.1.20"),
    )
    assertEquals(listOf("192.168.1.10", "192.168.1.20", "192.168.1.30"), result.map { it.host })
  }

  @Test
  fun `deduplication keeps first occurrence`() = runTest {
    val result = discover(
      server("First", "10.0.0.1"),
      server("Second", "10.0.0.1", tokenRequired = true),
    )
    assertEquals(listOf("First"), result.map { it.name })
  }

  @Test
  fun `mixed duplicates and unique servers`() = runTest {
    val result = discover(
      server("A", "10.0.0.2"),
      server("B", "10.0.0.1"),
      server("A dup", "10.0.0.2", tokenRequired = true),
      server("C", "10.0.0.3", 9000),
    )
    // Sorted by host, keeping the first of the duplicates.
    assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), result.map { it.host })
    assertEquals("A", result[1].name)
  }
}
