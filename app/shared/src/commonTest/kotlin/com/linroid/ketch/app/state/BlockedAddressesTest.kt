package com.linroid.ketch.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockedAddressesTest {
  @Test
  fun isBlockedAddress_addressesBlocksAndRanges_areTaken() {
    for (entry in listOf("203.0.113.7", "203.0.113.0/24", "0.0.0.0/0", "203.0.113.1-203.0.113.99",
      "2001:db8::1", "2001:db8::/32", "::1", "2001:db8:0:0:0:0:0:1", "2001:db8::1-2001:db8::ff")) {
      assertTrue(isBlockedAddress(entry), entry)
    }
  }

  @Test
  fun isBlockedAddress_namesAndMalformedEntries_areRefused() {
    for (entry in listOf("example.com", "203.0.113", "203.0.113.256", "203.0.113.0/33",
      "2001:db8::/129", "203.0.113.1-2001:db8::1", "2001:db8:::1", "1:2:3", "12345::1",
      "203.0.113.0/", "")) {
      assertFalse(isBlockedAddress(entry), entry)
    }
  }

  @Test
  fun addBlockedAddresses_pastedList_addsNewOnesAndReturnsTheRest() {
    val added = addBlockedAddresses(
      listOf("203.0.113.7"),
      "203.0.113.7, 198.51.100.0/24\nbad.example  2001:db8::/32;198.51.100.0/24",
    )

    assertEquals(listOf("203.0.113.7", "198.51.100.0/24", "2001:db8::/32"), added.addresses)
    assertEquals(listOf("bad.example"), added.rejected)
  }
}
