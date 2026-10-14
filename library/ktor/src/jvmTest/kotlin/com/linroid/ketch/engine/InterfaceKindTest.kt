package com.linroid.ketch.engine

import com.linroid.ketch.api.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceKindTest {
  @Test
  fun parseHardwarePorts_mapsDevicesToPorts() {
    val output = """

      Hardware Port: Wi-Fi
      Device: en0
      Ethernet Address: 00:00:00:00:00:01

      Hardware Port: Thunderbolt Bridge
      Device: bridge0
      Ethernet Address: 00:00:00:00:00:02

      VLAN Configurations
      ===================
    """.trimIndent()
    assertEquals(
      mapOf("en0" to "Wi-Fi", "bridge0" to "Thunderbolt Bridge"),
      parseHardwarePorts(output),
    )
  }

  @Test
  fun interfaceKind_macHardwarePorts() {
    assertEquals(NetworkKind.WIFI, interfaceKind("en0", "en0", "Wi-Fi"))
    assertEquals(NetworkKind.ETHERNET, interfaceKind("en0", "en0", "Ethernet"))
    assertEquals(NetworkKind.ETHERNET, interfaceKind("en7", "en7", "USB 10/100/1000 LAN"))
    assertEquals(NetworkKind.CELLULAR, interfaceKind("en8", "en8", "iPhone USB"))
    assertEquals(NetworkKind.OTHER, interfaceKind("bridge0", "bridge0", "Thunderbolt Bridge"))
  }

  @Test
  fun interfaceKind_macWithoutPort_tunnelsAreVpn() {
    assertEquals(NetworkKind.VPN, interfaceKind("utun3", "utun3"))
    assertEquals(NetworkKind.OTHER, interfaceKind("en0", "en0"))
  }

  @Test
  fun interfaceKind_linuxNames() {
    assertEquals(NetworkKind.WIFI, interfaceKind("wlp2s0", "wlp2s0"))
    assertEquals(NetworkKind.ETHERNET, interfaceKind("enp3s0", "enp3s0"))
    assertEquals(NetworkKind.CELLULAR, interfaceKind("wwan0", "wwan0"))
    assertEquals(NetworkKind.VPN, interfaceKind("wg0", "wg0"))
    assertEquals(NetworkKind.OTHER, interfaceKind("docker0", "docker0"))
  }

  @Test
  fun interfaceKind_windowsAdapters() {
    assertEquals(NetworkKind.WIFI, interfaceKind("wlan1", "Intel(R) Wi-Fi 6 AX201 160MHz"))
    assertEquals(NetworkKind.ETHERNET, interfaceKind("eth3", "Realtek PCIe GbE Family Controller"))
    assertEquals(NetworkKind.OTHER, interfaceKind("eth9", "Hyper-V Virtual Ethernet Adapter"))
    assertEquals(NetworkKind.VPN, interfaceKind("net7", "WireGuard Tunnel"))
  }
}
