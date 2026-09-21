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
    manager.allNetworks.mapNotNull { network ->
      if (!isAvailable(network)) return@mapNotNull null
      val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
      NetworkInterfaceInfo(
        id = network.networkHandle.toString(),
        name = properties.interfaceName ?: "Network ${network.networkHandle}",
        addresses = properties.linkAddresses.mapNotNull { it.address.hostAddress },
      )
    }.sortedBy { it.id }

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
