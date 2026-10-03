package com.linroid.ketch.app.util

import com.linroid.ketch.api.NetworkInterfaceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {
  private val token = "3f2a9c1e5b7d4a60b8e2c9f1a3d5e7b9"
  private val link = PairingLink(
    host = "192.168.1.20",
    port = 8642,
    token = token,
    name = "Lins-MacBook-Pro",
  )

  @Test
  fun toUri_withToken_putsTheTokenOnlyInTheFragment() {
    val uri = link.toUri()

    assertEquals(
      "ketch://pair?host=192.168.1.20&port=8642&name=Lins-MacBook-Pro#token=$token",
      uri,
    )
    assertFalse(token in uri.substringBefore('#'))
    assertEquals("token=$token", uri.substringAfter('#'))
  }

  @Test
  fun webAppUrl_withToken_putsTheTokenOnlyInTheFragment() {
    val url = link.webAppUrl()

    assertEquals("http://192.168.1.20:8642/#token=$token", url)
    assertFalse(token in url.substringBefore('#'))
  }

  @Test
  fun parse_pairLink_roundTrips() {
    val links = listOf(
      link,
      link.copy(token = null, name = null),
      link.copy(name = "Lin's MacBook Pro · 書斎", secure = true, port = 9443),
      link.copy(host = "fe80::1c2a:3bff:fe4d:5e6f", name = "NAS & media"),
      link.copy(host = "nas.local", token = "a+b/c=d&e#f"),
    )
    for (expected in links) {
      assertEquals(expected, PairingLink.parse(expected.toUri()), expected.toUri())
    }
  }

  @Test
  fun parse_webAppUrl_roundTrips() {
    val links = listOf(
      link.copy(name = null),
      link.copy(name = null, token = null),
      link.copy(name = null, secure = true, host = "ketch.example.com", port = 443),
      link.copy(name = null, host = "::1"),
    )
    for (expected in links) {
      assertEquals(expected, PairingLink.parse(expected.webAppUrl()), expected.webAppUrl())
    }
  }

  @Test
  fun parse_address_roundTrips() {
    val links = listOf(
      PairingLink("nas.local", 8642),
      PairingLink("nas.local", 9000),
      PairingLink("192.168.1.20", 1),
      PairingLink("fe80::1", 65535),
    )
    for (expected in links) {
      assertEquals(expected, PairingLink.parse(expected.address), expected.address)
    }
  }

  @Test
  fun parse_hostWithoutPort_usesTheDefaultPort() {
    assertEquals(PairingLink("nas.local", PairingLink.DEFAULT_PORT), PairingLink.parse("nas.local"))
    assertEquals(PairingLink("fe80::1", PairingLink.DEFAULT_PORT), PairingLink.parse("fe80::1"))
    assertEquals(PairingLink("::1", PairingLink.DEFAULT_PORT), PairingLink.parse("[::1]"))
  }

  @Test
  fun parse_webAddressWithoutPort_usesThePortOfItsScheme() {
    assertEquals(PairingLink("nas.local", 80), PairingLink.parse("http://nas.local"))
    assertEquals(
      PairingLink("nas.local", 443, token = "abc", secure = true),
      PairingLink.parse(" HTTPS://nas.local/ketch/?x=1#token=abc "),
    )
  }

  @Test
  fun parse_pairLinkWithoutPort_usesTheDefaultPort() {
    assertEquals(
      PairingLink("nas.local", PairingLink.DEFAULT_PORT, token = "abc"),
      PairingLink.parse("KETCH://Pair/?host=nas.local#token=abc"),
    )
  }

  @Test
  fun parse_otherText_returnsNull() {
    val rejected = listOf(
      "",
      "   ",
      "two words",
      "ketch://open?host=nas.local",
      "ketch://pair?port=8642",
      "ketch://pair?host=nas.local&port=http",
      "ftp://nas.local",
      "nas.local:99999",
      "nas.local:0",
      "nas.local:port",
      "[fe80::1",
      "[fe80::1]8642",
      "user@nas.local",
      "http://",
    )
    for (text in rejected) {
      assertNull(PairingLink.parse(text), text)
    }
  }

  @Test
  fun address_ipv6Host_isBracketed() {
    assertEquals("[fe80::1]:8642", PairingLink("fe80::1").address)
    assertEquals("nas.local:8642", PairingLink("nas.local").address)
  }

  @Test
  fun ipv4Addresses_mixedInterfaces_listPrivateFirst() {
    val interfaces = listOf(
      NetworkInterfaceInfo("lo0", "lo0", listOf("127.0.0.1", "::1")),
      NetworkInterfaceInfo("utun3", "utun3", listOf("100.101.7.12")),
      NetworkInterfaceInfo("en0", "en0", listOf("fe80::1", "192.168.1.20")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4", "169.254.3.9")),
      NetworkInterfaceInfo("en8", "en8", listOf("192.168.1.20")),
    )

    assertEquals(
      listOf("192.168.1.20", "10.0.0.4", "100.101.7.12"),
      ipv4Addresses(interfaces),
    )
  }

  @Test
  fun pairingAddresses_vmBridgesAndVpns_areLeftOut() {
    val interfaces = listOf(
      NetworkInterfaceInfo("bridge100", "bridge100", listOf("192.168.139.3")),
      NetworkInterfaceInfo("bridge101", "bridge101", listOf("172.18.0.0")),
      NetworkInterfaceInfo("docker0", "docker0", listOf("172.17.0.1")),
      NetworkInterfaceInfo("en0", "en0", listOf("192.168.31.11")),
      NetworkInterfaceInfo("en7", "en7", listOf("10.0.0.4")),
      NetworkInterfaceInfo("eth3", "Hyper-V Virtual Ethernet Adapter", listOf("172.24.0.1")),
      NetworkInterfaceInfo("utun3", "utun3", listOf("100.101.7.12")),
      NetworkInterfaceInfo("wg0", "wg0", listOf("10.8.0.2")),
    )

    assertEquals(listOf("192.168.31.11", "10.0.0.4"), pairingAddresses(interfaces))
  }

  @Test
  fun pairingAddresses_androidMobileDataAndVpn_areLeftOut() {
    val interfaces = listOf(
      NetworkInterfaceInfo("100", "Mobile data", listOf("10.64.12.9")),
      NetworkInterfaceInfo("101", "VPN", listOf("10.8.0.2")),
      NetworkInterfaceInfo("102", "Wi-Fi", listOf("192.168.1.42")),
    )

    assertEquals(listOf("192.168.1.42"), pairingAddresses(interfaces))
  }

  @Test
  fun pairingAddresses_hyperVExternalSwitch_fallsBackToTheVirtualAdapter() {
    val interfaces = listOf(
      NetworkInterfaceInfo("eth1", "Intel(R) Ethernet Connection I219-V", listOf("fe80::1")),
      NetworkInterfaceInfo("eth4", "Hyper-V Virtual Ethernet Adapter #2", listOf("192.168.1.30")),
      NetworkInterfaceInfo("eth6", "Wintun Userspace Tunnel", listOf("100.101.7.12")),
    )

    assertEquals(listOf("192.168.1.30", "100.101.7.12"), pairingAddresses(interfaces))
  }

  @Test
  fun isPrivateIpv4_rangeEdges_matchTheRfc1918Blocks() {
    assertTrue(isPrivateIpv4("10.255.0.1"))
    assertTrue(isPrivateIpv4("172.16.0.1"))
    assertTrue(isPrivateIpv4("172.31.255.1"))
    assertFalse(isPrivateIpv4("172.32.0.1"))
    assertTrue(isPrivateIpv4("192.168.0.1"))
    assertFalse(isPrivateIpv4("192.169.0.1"))
    assertFalse(isPrivateIpv4("fe80::1"))
    assertFalse(isPrivateIpv4("10.0.0"))
  }
}
