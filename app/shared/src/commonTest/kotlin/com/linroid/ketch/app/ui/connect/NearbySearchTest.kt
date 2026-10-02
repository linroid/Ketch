package com.linroid.ketch.app.ui.connect

import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.MdnsDiscoverer
import com.linroid.ketch.app.instance.NoOpMdnsDiscoverer
import com.linroid.ketch.config.ServerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NearbySearchTest {

  /** Answers each round with the next of [rounds], or fails with [failure]. */
  private class RoundDiscoverer(
    private val rounds: List<List<DiscoveredServer>>,
    private val failure: Exception? = null,
  ) : MdnsDiscoverer {
    val calls = mutableListOf<Pair<String, Long>>()

    override suspend fun discover(serviceType: String, timeoutMs: Long): List<DiscoveredServer> {
      calls += serviceType to timeoutMs
      failure?.let { throw it }
      return rounds[calls.size - 1]
    }
  }

  private val nas = DiscoveredServer("NAS-Basement", "192.168.1.10", 8642, tokenRequired = false)
  private val den = DiscoveredServer("Den-PC", "192.168.1.7", 8642, tokenRequired = true)

  private fun TestScope.search(discoverer: MdnsDiscoverer) = NearbySearch(
    discoverer = discoverer,
    scope = this,
    rounds = listOf(1.seconds, 2.seconds),
    dispatcher = StandardTestDispatcher(testScheduler),
  )

  @Test
  fun search_twoRounds_listsDevicesInTheOrderFound() = runTest {
    val renamed = nas.copy(name = "NAS")
    val discoverer = RoundDiscoverer(listOf(listOf(nas), listOf(den, renamed)))
    val search = search(discoverer)

    search.search()
    runCurrent()

    assertEquals(listOf(renamed, den), search.servers)
    assertFalse(search.searching)
    assertTrue(search.searched)
    assertEquals(
      listOf(ServerConfig.MDNS_SERVICE_TYPE to 1000L, ServerConfig.MDNS_SERVICE_TYPE to 2000L),
      discoverer.calls,
    )
  }

  @Test
  fun search_againAfterAFirstSearch_keepsWhatWasFound() = runTest {
    val discoverer = RoundDiscoverer(listOf(listOf(nas), emptyList(), emptyList(), listOf(den)))
    val search = search(discoverer)
    search.search()
    runCurrent()

    search.search()
    runCurrent()

    assertEquals(listOf(nas, den), search.servers)
  }

  @Test
  fun search_browsingFails_reportsIt() = runTest {
    val search = search(RoundDiscoverer(emptyList(), IllegalStateException("No network")))

    search.search()
    runCurrent()

    assertEquals("Couldn't search your network", search.error)
    assertTrue(search.searched)
    assertFalse(search.searching)
  }

  @Test
  fun search_unsupportedPlatform_doesNothing() = runTest {
    val search = search(NoOpMdnsDiscoverer)

    search.search()
    runCurrent()

    assertFalse(search.supported)
    assertFalse(search.searched)
  }
}
