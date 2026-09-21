package com.linroid.ketch.engine

import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.NetworkInterfaceProvider
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Creates an engine exposing JVM interface discovery and runtime selection through KetchApi.
 * Starts with system-default routing. Selected interfaces bind to their first usable IPv4 address,
 * or first IPv6 address if no IPv4 address exists. Loopback and link-local addresses are excluded.
 * Selection and binding otherwise have the same routing and DNS limits as [forLocalAddress].
 */
fun KtorHttpEngine.Companion.withNetworkInterfaces(
  logRequests: Boolean = true,
): ConfigurableNetworkHttpEngine = ConfigurableNetworkHttpEngine(
  JvmNetworkInterfaceProvider(logRequests)
)

internal class JvmNetworkInterfaceProvider(
  private val logRequests: Boolean,
) : NetworkInterfaceProvider {
  override suspend fun availableInterfaces(): List<NetworkInterfaceInfo> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
      .filter { it.isUp && !it.isLoopback }
      .mapNotNull { network ->
        val addresses = addresses(network)
        if (addresses.isEmpty()) null else NetworkInterfaceInfo(
          id = network.name,
          name = network.displayName ?: network.name,
          addresses = addresses.map { it.hostAddress },
        )
      }
      .sortedBy { it.id }

  override fun createDefaultEngine(): HttpEngine = KtorHttpEngine(logRequests = logRequests)

  override fun createEngine(networkInterface: NetworkInterfaceInfo): HttpEngine {
    val network = requireNotNull(NetworkInterface.getByName(networkInterface.id)) {
      "Interface is no longer available: ${networkInterface.id}"
    }
    require(network.isUp && !network.isLoopback) {
      "Interface is unavailable: ${networkInterface.id}"
    }
    val address = requireNotNull(addresses(network).firstOrNull()) {
      "Interface has no usable address: ${networkInterface.id}"
    }
    return KtorHttpEngine.forLocalAddress(address, logRequests)
  }

  private fun addresses(network: NetworkInterface): List<InetAddress> =
    network.inetAddresses.toList()
      .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
      .filter { !it.isMulticastAddress }
      .sortedWith(compareBy<InetAddress> { it !is Inet4Address }.thenBy { it.hostAddress })
}
