package com.linroid.ketch.torrent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TorrentSessionRegistryTest {
  @Test
  fun reserve_concurrentSameSwarm_onlyOneOwner() = runTest {
    val registry = TorrentSessionRegistry()
    val results = (0..10).map { id ->
      async { runCatching { registry.reserve("task-$id", "hash") }.isSuccess }
    }.awaitAll()
    assertEquals(1, results.count { it })
  }

  @Test
  fun release_oneTask_doesNotRemoveOtherSession() = runTest {
    val registry = TorrentSessionRegistry()
    val first = FakeTorrentSession("first")
    val second = FakeTorrentSession("second")
    registry.reserve("a", first.infoHash)
    registry.reserve("b", second.infoHash)
    registry.attach("a", first)
    registry.attach("b", second)
    assertSame(first, registry.session("a"))
    registry.release("a")
    assertNull(registry.session("a"))
    assertSame(second, registry.session("b"))
    registry.reserve("c", first.infoHash)
  }

  @Test
  fun attach_wrongSwarm_rejectedWithoutReplacingOwner() = runTest {
    val registry = TorrentSessionRegistry()
    registry.reserve("a", "first")
    assertFailsWith<IllegalStateException> {
      registry.attach("a", FakeTorrentSession("second"))
    }
    assertNull(registry.session("a"))
  }
}
