package com.linroid.ketch.torrent.net

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentIpFilterTest {
  @Test
  fun `single addresses blocks and ranges match what they cover`() {
    val filter = TorrentIpFilter.parse(listOf(
      "203.0.113.7", " 198.51.100.0/24 ", "192.0.2.10-192.0.2.20", "", "2001:db8::/32",
      "2001:db9::1",
    ))

    assertTrue(filter.blocks("203.0.113.7"))
    assertFalse(filter.blocks("203.0.113.8"))
    assertTrue(filter.blocks("198.51.100.0"))
    assertTrue(filter.blocks("198.51.100.255"))
    assertFalse(filter.blocks("198.51.101.0"))
    assertTrue(filter.blocks("192.0.2.10"))
    assertTrue(filter.blocks("192.0.2.20"))
    assertFalse(filter.blocks("192.0.2.21"))
    assertFalse(filter.blocks("192.0.2.9"))
    assertTrue(filter.blocks("2001:db8:1::5"))
    assertFalse(filter.blocks("2001:db7:ffff::1"))
    assertTrue(filter.blocks("[2001:db9::1]"))
    assertFalse(filter.blocks("2001:db9::2"))
  }

  @Test
  fun `a mapped address is the IPv4 one and names are never blocked`() {
    val filter = TorrentIpFilter.parse(listOf("203.0.113.0/24", "::ffff:192.0.2.1"))

    assertTrue(filter.blocks("::ffff:203.0.113.9"))
    assertTrue(filter.blocks("192.0.2.1"))
    assertFalse(filter.blocks("tracker.example"))
    assertFalse(filter.blocks(""))
    // Families never match each other.
    assertFalse(filter.blocks("::203.0.113.9"))
  }

  @Test
  fun `prefix bounds cover everything or one address`() {
    assertTrue(TorrentIpFilter.parse(listOf("0.0.0.0/0")).blocks("8.8.8.8"))
    assertFalse(TorrentIpFilter.parse(listOf("0.0.0.0/0")).blocks("2001:db8::1"))
    val one = TorrentIpFilter.parse(listOf("203.0.113.7/32"))
    assertTrue(one.blocks("203.0.113.7"))
    assertFalse(one.blocks("203.0.113.6"))
    assertTrue(TorrentIpFilter.NONE.isEmpty)
    assertFalse(TorrentIpFilter.NONE.blocks("203.0.113.7"))
  }

  @Test
  fun `entries that are no address block or range are refused`() {
    for (entry in listOf("example.com", "203.0.113.0/33", "203.0.113.9-203.0.113.1",
      "203.0.113.1-2001:db8::1", "2001:db8::/129", "1.2.3", "203.0.113.0/x")) {
      assertFalse(TorrentIpFilter.isValid(entry), entry)
      assertFailsWith<IllegalArgumentException> { TorrentIpFilter.parse(listOf(entry)) }
    }
    assertTrue(TorrentIpFilter.isValid(" 203.0.113.0/24 "))
    assertFailsWith<IllegalArgumentException> {
      TorrentIpFilter.parse(List(TorrentIpFilter.MAX_ENTRIES + 1) { "203.0.113.7" })
    }
  }
}
