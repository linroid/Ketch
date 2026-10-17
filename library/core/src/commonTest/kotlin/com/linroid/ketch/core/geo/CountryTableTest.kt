package com.linroid.ketch.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountryTableTest {
  private val table = CountryTable.parse(
    sequenceOf(
      "0.0.0.0,0.255.255.255,ZZ",
      "1.0.0.0,1.0.0.255,AU",
      "1.0.1.0,1.0.3.255,CN",
      "1.0.4.0,1.0.7.255,AU",
      "128.0.0.0,255.255.255.255,DE",
      "::,1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,ZZ",
      "2001:db8::,2001:db8:ffff:ffff:ffff:ffff:ffff:ffff,NL",
      "2001:db9::,ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,US",
      "not,an,entry",
    ),
  )

  @Test fun ipv4_findsTheRangeHoldingTheAddress() {
    assertEquals("AU", table.countryOf("1.0.0.1"))
    assertEquals("CN", table.countryOf("1.0.2.200"))
    assertEquals("AU", table.countryOf("1.0.7.255"))
    assertEquals("DE", table.countryOf("255.255.255.255"))
  }

  @Test fun reservedRangesAndNames_haveNoCountry() {
    assertNull(table.countryOf("0.1.2.3"))
    assertNull(table.countryOf("example.com"))
    assertNull(table.countryOf("1.2.3"))
    assertNull(table.countryOf("::1"))
  }

  @Test fun ipv6_findsTheRangeOfItsNetwork() {
    assertEquals("NL", table.countryOf("2001:db8::1"))
    assertEquals("US", table.countryOf("2001:db9:1:2:3:4:5:6"))
    assertEquals("US", table.countryOf("fe80::1%en0"))
  }

  @Test fun ipv4MappedIpv6_readsAsIpv4() {
    assertEquals("CN", table.countryOf("::ffff:1.0.1.1"))
  }
}
