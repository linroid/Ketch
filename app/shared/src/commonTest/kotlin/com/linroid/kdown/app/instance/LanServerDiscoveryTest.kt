package com.linroid.kdown.app.instance

import com.linroid.kdown.api.config.ServerConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeMdnsDiscoverer(
  private val results: List<DiscoveredServer> = emptyList(),
) : MdnsDiscoverer {
  var lastServiceType: String? = null
    private set
  var lastTimeoutMs: Long? = null
    private set
  var discoverCallCount = 0
    private set

  override suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer> {
    discoverCallCount++
    lastServiceType = serviceType
    lastTimeoutMs = timeoutMs
    return results
  }
}

class LanServerDiscoveryTest {

  @Test
  fun `empty discovery returns empty list`() = runTest {
    val fake = FakeMdnsDiscoverer()
    val discovery = LanServerDiscovery(fake)
    val result = discovery.discover()
    assertTrue(result.isEmpty())
    assertEquals(1, fake.discoverCallCount)
  }

  @Test
  fun `passes correct service type`() = runTest {
    val fake = FakeMdnsDiscoverer()
    val discovery = LanServerDiscovery(fake)
    discovery.discover()
    assertEquals(ServerConfig.MDNS_SERVICE_TYPE, fake.lastServiceType)
  }

  @Test
  fun `single server passes through`() = runTest {
    val server = DiscoveredServer(
      name = "KDown",
      host = "192.168.1.5",
      port = 8642,
      tokenRequired = false,
    )
    val fake = FakeMdnsDiscoverer(listOf(server))
    val discovery = LanServerDiscovery(fake)
    val result = discovery.discover()
    assertEquals(1, result.size)
    assertEquals(server, result[0])
  }

  @Test
  fun `duplicates with same host and port are deduplicated`() =
    runTest {
      val servers = listOf(
        DiscoveredServer(
          name = "KDown A",
          host = "192.168.1.5",
          port = 8642,
          tokenRequired = false,
        ),
        DiscoveredServer(
          name = "KDown B",
          host = "192.168.1.5",
          port = 8642,
          tokenRequired = true,
        ),
      )
      val fake = FakeMdnsDiscoverer(servers)
      val discovery = LanServerDiscovery(fake)
      val result = discovery.discover()
      assertEquals(1, result.size)
    }

  @Test
  fun `different ports on same host are not deduplicated`() =
    runTest {
      val servers = listOf(
        DiscoveredServer(
          name = "KDown A",
          host = "192.168.1.5",
          port = 8642,
          tokenRequired = false,
        ),
        DiscoveredServer(
          name = "KDown B",
          host = "192.168.1.5",
          port = 9000,
          tokenRequired = false,
        ),
      )
      val fake = FakeMdnsDiscoverer(servers)
      val discovery = LanServerDiscovery(fake)
      val result = discovery.discover()
      assertEquals(2, result.size)
    }

  @Test
  fun `results are sorted by host`() = runTest {
    val servers = listOf(
      DiscoveredServer(
        name = "C",
        host = "192.168.1.30",
        port = 8642,
        tokenRequired = false,
      ),
      DiscoveredServer(
        name = "A",
        host = "192.168.1.10",
        port = 8642,
        tokenRequired = false,
      ),
      DiscoveredServer(
        name = "B",
        host = "192.168.1.20",
        port = 8642,
        tokenRequired = false,
      ),
    )
    val fake = FakeMdnsDiscoverer(servers)
    val discovery = LanServerDiscovery(fake)
    val result = discovery.discover()
    assertEquals(3, result.size)
    assertEquals("192.168.1.10", result[0].host)
    assertEquals("192.168.1.20", result[1].host)
    assertEquals("192.168.1.30", result[2].host)
  }

  @Test
  fun `deduplication keeps first occurrence`() = runTest {
    val servers = listOf(
      DiscoveredServer(
        name = "First",
        host = "10.0.0.1",
        port = 8642,
        tokenRequired = false,
      ),
      DiscoveredServer(
        name = "Second",
        host = "10.0.0.1",
        port = 8642,
        tokenRequired = true,
      ),
    )
    val fake = FakeMdnsDiscoverer(servers)
    val discovery = LanServerDiscovery(fake)
    val result = discovery.discover()
    assertEquals(1, result.size)
    assertEquals("First", result[0].name)
  }

  @Test
  fun `mixed duplicates and unique servers`() = runTest {
    val servers = listOf(
      DiscoveredServer(
        name = "A",
        host = "10.0.0.2",
        port = 8642,
        tokenRequired = false,
      ),
      DiscoveredServer(
        name = "B",
        host = "10.0.0.1",
        port = 8642,
        tokenRequired = false,
      ),
      DiscoveredServer(
        name = "A dup",
        host = "10.0.0.2",
        port = 8642,
        tokenRequired = true,
      ),
      DiscoveredServer(
        name = "C",
        host = "10.0.0.3",
        port = 9000,
        tokenRequired = false,
      ),
    )
    val fake = FakeMdnsDiscoverer(servers)
    val discovery = LanServerDiscovery(fake)
    val result = discovery.discover()
    assertEquals(3, result.size)
    // Sorted by host
    assertEquals("10.0.0.1", result[0].host)
    assertEquals("10.0.0.2", result[1].host)
    assertEquals("10.0.0.3", result[2].host)
    // Dedup kept first occurrence
    assertEquals("A", result[1].name)
  }
}
