package com.linroid.ketch.engine

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.NetworkInterfaceProvider

/**
 * Creates an engine exposing available Android networks and runtime selection through KetchApi.
 * Requires INTERNET and ACCESS_NETWORK_STATE permissions. Does not request or retain networks;
 * the app owns ConnectivityManager callbacks when keeping a non-default network alive is needed.
 * Network IDs are scoped to the current network lifetime and must be rediscovered after reconnects.
 * Networks are named by their transport, "Wi-Fi" or "Mobile data", not by kernel interface.
 * Starts with system-default routing; selected networks use [forNetwork] for sockets and DNS.
 */
fun KtorHttpEngine.Companion.withNetworkInterfaces(
  connectivityManager: ConnectivityManager,
  logRequests: Boolean = true,
): ConfigurableNetworkHttpEngine = ConfigurableNetworkHttpEngine(
  AndroidNetworkInterfaceProvider(connectivityManager, logRequests)
)

// Point-in-time discovery; callbacks and network retention belong to the app.
@Suppress("DEPRECATION")
internal class AndroidNetworkInterfaceProvider(
  private val manager: ConnectivityManager,
  private val logRequests: Boolean,
) : NetworkInterfaceProvider {
  override suspend fun availableInterfaces(): List<NetworkInterfaceInfo> =
    nameNetworks(
      manager.allNetworks.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
          return@mapNotNull null
        }
        val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
        DiscoveredNetwork(
          id = network.networkHandle.toString(),
          kind = networkKind(capabilities::hasTransport),
          interfaceName = properties.interfaceName,
          addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress },
        )
      }
    ).sortedBy { it.id }

  override fun createDefaultEngine(): HttpEngine = KtorHttpEngine(logRequests = logRequests)

  override fun createEngine(networkInterface: NetworkInterfaceInfo): HttpEngine {
    val network = requireNotNull(manager.allNetworks.find {
      it.networkHandle.toString() == networkInterface.id && isAvailable(it)
    }) { "Unknown or unavailable network: ${networkInterface.id}" }
    return KtorHttpEngine.forNetwork(network, logRequests)
  }

  private fun isAvailable(network: Network): Boolean =
    manager.getNetworkCapabilities(network)
      ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/** A network found by [AndroidNetworkInterfaceProvider], before it is named. */
internal data class DiscoveredNetwork(
  val id: String,
  /** What Android's settings call this kind of network, from [networkKind]. */
  val kind: String?,
  /** Kernel interface such as "wlan0" or "rmnet_data3". */
  val interfaceName: String?,
  val addresses: List<String>,
)

/**
 * What Android's settings call a network with these transports, such as "Wi-Fi" or
 * "Mobile data"; `null` for a transport without a familiar name.
 */
internal fun networkKind(hasTransport: (Int) -> Boolean): String? = when {
  // A VPN also reports the transports of the networks it runs over.
  hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
  hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
  hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
  hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
  hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
  else -> null
}

/**
 * Names [networks] by their kind, falling back to the interface for an unknown kind. Networks of
 * the same kind, such as two Wi-Fi connections, add their interface: "Wi-Fi (wlan1)".
 */
internal fun nameNetworks(networks: List<DiscoveredNetwork>): List<NetworkInterfaceInfo> {
  val names = networks.map { it.kind ?: it.interfaceName ?: "Network ${it.id}" }
  val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
  return networks.mapIndexed { index, network ->
    val name = names[index]
    NetworkInterfaceInfo(
      id = network.id,
      name = if (name in repeated && network.kind != null && network.interfaceName != null) {
        "$name (${network.interfaceName})"
      } else {
        name
      },
      addresses = network.addresses,
    )
  }
}
