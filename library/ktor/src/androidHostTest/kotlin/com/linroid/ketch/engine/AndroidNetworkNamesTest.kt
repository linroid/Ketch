package com.linroid.ketch.engine

import android.net.NetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidNetworkNamesTest {
  @Test
  fun networkKind_wifiAndCellular_settingsNames() {
    assertEquals("Wi-Fi", networkKind(transports(NetworkCapabilities.TRANSPORT_WIFI)))
    assertEquals("Mobile data", networkKind(transports(NetworkCapabilities.TRANSPORT_CELLULAR)))
  }

  @Test
  fun networkKind_vpnOverWifi_isVpn() {
    val kind = networkKind(
      transports(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
    )
    assertEquals("VPN", kind)
  }

  @Test
  fun networkKind_unknownTransport_isNull() {
    assertNull(networkKind(transports(NetworkCapabilities.TRANSPORT_WIFI_AWARE)))
  }

  @Test
  fun nameNetworks_distinctKinds_hideInterfaces() {
    val names = nameNetworks(
      listOf(
        network("1", "Wi-Fi", "wlan0"),
        network("2", "Mobile data", "rmnet_data3"),
      )
    ).map { it.name }
    assertEquals(listOf("Wi-Fi", "Mobile data"), names)
  }

  @Test
  fun nameNetworks_sameKind_addInterfaces() {
    val names = nameNetworks(
      listOf(
        network("1", "Wi-Fi", "wlan0"),
        network("2", "Wi-Fi", "wlan1"),
        network("3", "Mobile data", "rmnet_data3"),
      )
    ).map { it.name }
    assertEquals(listOf("Wi-Fi (wlan0)", "Wi-Fi (wlan1)", "Mobile data"), names)
  }

  @Test
  fun nameNetworks_unknownKind_fallsBackToInterfaceThenId() {
    val names = nameNetworks(
      listOf(network("1", null, "lowpan0"), network("2", null, null))
    ).map { it.name }
    assertEquals(listOf("lowpan0", "Network 2"), names)
  }

  private fun transports(vararg types: Int): (Int) -> Boolean = { it in types }

  private fun network(id: String, kind: String?, interfaceName: String?) =
    DiscoveredNetwork(id, kind, interfaceName, listOf("192.0.2.$id"))
}
