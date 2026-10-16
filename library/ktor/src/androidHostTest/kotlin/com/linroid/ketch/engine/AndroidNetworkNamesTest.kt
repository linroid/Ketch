package com.linroid.ketch.engine

import android.net.NetworkCapabilities
import com.linroid.ketch.api.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidNetworkNamesTest {
  @Test
  fun networkKind_wifiAndCellular_kinds() {
    assertEquals(NetworkKind.WIFI, networkKind(transports(NetworkCapabilities.TRANSPORT_WIFI)))
    assertEquals(
      NetworkKind.CELLULAR,
      networkKind(transports(NetworkCapabilities.TRANSPORT_CELLULAR)),
    )
  }

  @Test
  fun networkKind_vpnOverWifi_isVpn() {
    val kind = networkKind(
      transports(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
    )
    assertEquals(NetworkKind.VPN, kind)
  }

  @Test
  fun networkKind_unknownTransport_isOther() {
    assertEquals(
      NetworkKind.OTHER,
      networkKind(transports(NetworkCapabilities.TRANSPORT_WIFI_AWARE)),
    )
  }

  @Test
  fun nameNetworks_distinctKinds_hideInterfaces() {
    val names = nameNetworks(
      listOf(
        network("1", NetworkKind.WIFI, "wlan0"),
        network("2", NetworkKind.CELLULAR, "rmnet_data3"),
      )
    ).map { it.name }
    assertEquals(listOf("Wi-Fi", "Mobile data"), names)
  }

  @Test
  fun nameNetworks_sameKind_addInterfaces() {
    val names = nameNetworks(
      listOf(
        network("1", NetworkKind.WIFI, "wlan0"),
        network("2", NetworkKind.WIFI, "wlan1"),
        network("3", NetworkKind.CELLULAR, "rmnet_data3"),
      )
    ).map { it.name }
    assertEquals(listOf("Wi-Fi (wlan0)", "Wi-Fi (wlan1)", "Mobile data"), names)
  }

  @Test
  fun nameNetworks_keepsKinds() {
    val kinds = nameNetworks(
      listOf(network("1", NetworkKind.WIFI, "wlan0"), network("2", NetworkKind.CELLULAR, null))
    ).map { it.kind }
    assertEquals(listOf(NetworkKind.WIFI, NetworkKind.CELLULAR), kinds)
  }

  @Test
  fun nameNetworks_unknownKind_fallsBackToInterfaceThenId() {
    val names = nameNetworks(
      listOf(network("1", NetworkKind.OTHER, "lowpan0"), network("2", NetworkKind.OTHER, null))
    ).map { it.name }
    assertEquals(listOf("lowpan0", "Network 2"), names)
  }

  private fun transports(vararg types: Int): (Int) -> Boolean = { it in types }

  private fun network(id: String, kind: NetworkKind, interfaceName: String?) =
    DiscoveredNetwork(id, kind, interfaceName, listOf("192.0.2.$id"))
}
