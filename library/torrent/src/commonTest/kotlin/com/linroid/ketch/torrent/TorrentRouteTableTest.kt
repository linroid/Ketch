package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorrentRouteTableTest {
  private val sink = TorrentIncomingSink { false }

  private fun bytes(seed: Int, size: Int) = ByteArray(size) { (seed + it).toByte() }

  @Test
  fun registerRejectsTagOwnedInTheOtherTagSpace() {
    val routes = TorrentRouteTable()
    val prefix = bytes(1, 20)
    val v1 = TorrentIdentity(v1 = InfoHash.fromBytes(prefix))
    // A pure v2 info hash whose truncated wire form equals that v1 hash.
    val v2 = TorrentIdentity(v2 = V2InfoHash.fromBytes(prefix + bytes(99, 12)))
    assertEquals(TorrentRouteTable.tagsOf(v1), TorrentRouteTable.tagsOf(v2))
    val first = routes.register("v1", TorrentRouteTable.tagsOf(v1), sink)
    val error = assertFailsWith<IllegalStateException> {
      routes.register("v2", TorrentRouteTable.tagsOf(v2), sink)
    }
    assertEquals("Torrent already has an active owner", error.message)
    assertSame(first, routes.lookup(prefix.toByteString()))
  }

  @Test
  fun registerIsAtomicWhenOneTagCollides() {
    val routes = TorrentRouteTable()
    val v2 = bytes(1, 20).toByteString()
    val v1 = bytes(50, 20).toByteString()
    val owner = routes.register("pure", listOf(v2), sink)
    assertFailsWith<IllegalStateException> { routes.register("hybrid", listOf(v1, v2), sink) }
    // The free v1 tag was validated, not inserted.
    assertNull(routes.lookup(v1))
    assertEquals(setOf(v2), routes.tags)
    assertSame(owner, routes.lookup(v2))
    routes.register("v1", listOf(v1), sink)
  }

  @Test
  fun registerRejectsAmbiguousTags() {
    val routes = TorrentRouteTable()
    val tag = bytes(1, 20).toByteString()
    for (tags in listOf(emptyList(), listOf(tag, tag), listOf(bytes(1, 19).toByteString()),
      listOf(tag, bytes(2, 20).toByteString(), bytes(3, 20).toByteString()))) {
      assertFailsWith<IllegalArgumentException> { routes.register("bad", tags, sink) }
    }
    assertTrue(routes.tags.isEmpty())
  }

  @Test
  fun stoppedClaimKeepsTagsReservedUntilRelease() {
    val routes = TorrentRouteTable()
    val tags = listOf(bytes(1, 20).toByteString(), bytes(2, 20).toByteString())
    val claim = routes.register("hybrid", tags, sink)
    routes.stopAccepting(claim)
    tags.forEach { assertNull(routes.lookup(it)) }
    assertFailsWith<IllegalStateException> { routes.register("again", tags.take(1), sink) }
    routes.release(claim)
    val next = routes.register("again", tags.take(1), sink)
    assertSame(next, routes.lookup(tags[0]))
    // A late release of the old claim must not drop the new owner.
    routes.release(claim)
    assertSame(next, routes.lookup(tags[0]))
  }

  @Test
  fun snapshotIsImmutableAndExcludesStoppedClaims() {
    val routes = TorrentRouteTable()
    val pure = bytes(1, 20).toByteString()
    val hybrid = listOf(bytes(2, 20).toByteString(), bytes(3, 20).toByteString())
    val empty: Set<ByteString> = routes.tags
    routes.register("pure", listOf(pure), sink)
    val first = routes.tags
    val claim = routes.register("hybrid", hybrid, sink)
    assertTrue(empty.isEmpty())
    assertEquals(setOf(pure), first)
    assertEquals(setOf(pure) + hybrid, routes.tags)
    routes.stopAccepting(claim)
    assertEquals(setOf(pure), routes.tags)
    routes.clear()
    assertTrue(routes.tags.isEmpty())
    assertNull(routes.lookup(pure))
  }
}
