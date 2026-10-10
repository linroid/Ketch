package com.linroid.ketch.torrent

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerFlagBookTest {
  private val a = PeerEndpoint("10.0.0.1", 6881)
  private val b = PeerEndpoint("10.0.0.2", 6881)
  private val c = PeerEndpoint("10.0.0.3", 6881)

  @Test
  fun keepsTheNewestEndpointsUpToCapacity() {
    val book = PeerFlagBook(capacity = 2)
    book.record(mapOf(a to 0x10, b to 0x01))
    // Recording an endpoint again replaces its flags and makes it the newest.
    book.record(mapOf(a to 0x11))
    book.record(mapOf(c to 0x04))
    assertEquals(2, book.size)
    assertEquals(0x11, book[a])
    assertEquals(0, book[b])
    assertEquals(0x04, book[c])
  }

  @Test
  fun unknownEndpointHasNoFlags() {
    val book = PeerFlagBook()
    assertEquals(0, book[a])
    book.record(mapOf(a to 0))
    assertEquals(0, book[a])
    assertEquals(0, book[b])
  }
}
