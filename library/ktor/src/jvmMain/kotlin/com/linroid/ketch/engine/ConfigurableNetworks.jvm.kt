package com.linroid.ketch.engine

import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkKind
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.NetworkInterfaceProvider
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Creates an engine exposing JVM interface discovery and runtime selection through KetchApi.
 * Starts with system-default routing. Selected interfaces bind to their first usable IPv4 address,
 * or first IPv6 address if no IPv4 address exists. Loopback and link-local addresses are excluded.
 * Selection and binding otherwise have the same routing and DNS limits as [forLocalAddress].
 * Each interface's [NetworkKind] is guessed from its name, and on macOS from the system's
 * hardware ports, as `en0` is Wi-Fi on some Macs and Ethernet on others.
 */
fun KtorHttpEngine.Companion.withNetworkInterfaces(
  logRequests: Boolean = true,
  userAgent: String? = KtorHttpEngine.DEFAULT_USER_AGENT,
): ConfigurableNetworkHttpEngine = ConfigurableNetworkHttpEngine(
  JvmNetworkInterfaceProvider(logRequests, userAgent)
)

internal class JvmNetworkInterfaceProvider(
  private val logRequests: Boolean,
  private val userAgent: String? = KtorHttpEngine.DEFAULT_USER_AGENT,
) : NetworkInterfaceProvider {
  override suspend fun availableInterfaces(): List<NetworkInterfaceInfo> =
    withContext(KetchDispatchers.Default.disk) {
      val ports = if (isMac()) macHardwarePorts() else emptyMap()
      NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .mapNotNull { network ->
          val addresses = addresses(network)
          val displayName = network.displayName ?: network.name
          if (addresses.isEmpty()) null else NetworkInterfaceInfo(
            id = network.name,
            name = displayName,
            addresses = addresses.map { it.hostAddress },
            kind = interfaceKind(network.name, displayName, ports[network.name]),
          )
        }
        .sortedBy { it.id }
    }

  override fun createDefaultEngine(): HttpEngine =
    KtorHttpEngine(logRequests = logRequests, userAgent = userAgent)

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
    return KtorHttpEngine.forLocalAddress(address, logRequests, userAgent)
  }

  private fun addresses(network: NetworkInterface): List<InetAddress> =
    network.inetAddresses.toList()
      .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
      .filter { !it.isMulticastAddress }
      .sortedWith(compareBy<InetAddress> { it !is Inet4Address }.thenBy { it.hostAddress })
}

private val log = KetchLogger("NetworkHttpEngine")

private fun isMac(): Boolean =
  System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

/**
 * The hardware port of each macOS device, such as `en0` to "Wi-Fi", from
 * `networksetup -listallhardwareports`; empty when it cannot be run.
 */
private fun macHardwarePorts(): Map<String, String> = try {
  val process = ProcessBuilder("/usr/sbin/networksetup", "-listallhardwareports")
    .redirectErrorStream(true)
    .start()
  // Its few lines fit the pipe's buffer, so it never waits for us to read them.
  if (process.waitFor(2, TimeUnit.SECONDS)) {
    parseHardwarePorts(process.inputStream.bufferedReader().use { it.readText() })
  } else {
    process.destroyForcibly()
    emptyMap()
  }
} catch (e: Exception) {
  log.d { "Cannot list hardware ports: ${e.describeCauses()}" }
  emptyMap()
}

/** Reads `Hardware Port: Wi-Fi` / `Device: en0` pairs from `networksetup` output. */
internal fun parseHardwarePorts(output: String): Map<String, String> {
  val ports = mutableMapOf<String, String>()
  var port: String? = null
  for (line in output.lineSequence().map { it.trim() }) {
    when {
      line.startsWith("Hardware Port:") -> port = line.substringAfter(':').trim()
      line.startsWith("Device:") -> {
        val device = line.substringAfter(':').trim()
        if (port != null && device.isNotEmpty()) ports[device] = port
        port = null
      }
    }
  }
  return ports
}

/**
 * Guesses an interface's kind from its macOS [hardwarePort] when known, else from its [name]
 * (Linux `wlp2s0`, `enp3s0`; Windows `wlan0`, `eth2`; tunnels `utun3`, `wg0`) and
 * [displayName] (Windows adapter descriptions). Virtual adapters are [NetworkKind.OTHER].
 */
internal fun interfaceKind(
  name: String,
  displayName: String,
  hardwarePort: String? = null,
): NetworkKind {
  val id = name.lowercase()
  val label = displayName.lowercase()
  if (hardwarePort != null) {
    val port = hardwarePort.lowercase()
    return when {
      "wi-fi" in port || "airport" in port -> NetworkKind.WIFI
      "bluetooth" in port -> NetworkKind.BLUETOOTH
      "bridge" in port -> NetworkKind.OTHER
      // Tethering through the phone's mobile data.
      "iphone" in port -> NetworkKind.CELLULAR
      "ethernet" in port || "lan" in port -> NetworkKind.ETHERNET
      else -> NetworkKind.OTHER
    }
  }
  return when {
    VPN_PREFIXES.any { id.startsWith(it) } || VPN_LABELS.any { it in label } -> NetworkKind.VPN
    VIRTUAL_PREFIXES.any { id.startsWith(it) } || "virtual" in label -> NetworkKind.OTHER
    id.startsWith("wl") || WIFI_LABELS.any { it in label } -> NetworkKind.WIFI
    CELLULAR_PREFIXES.any { id.startsWith(it) } || CELLULAR_LABELS.any { it in label } ->
      NetworkKind.CELLULAR
    id.startsWith("bnep") || "bluetooth" in label -> NetworkKind.BLUETOOTH
    ETHERNET_PREFIXES.any { id.startsWith(it) } || "ethernet" in label -> NetworkKind.ETHERNET
    else -> NetworkKind.OTHER
  }
}

private val VPN_PREFIXES = listOf("utun", "tun", "tap", "ppp", "wg", "ipsec", "tailscale", "zt")
private val VPN_LABELS = listOf("vpn", "wireguard", "tailscale", "wintun", "tap-windows")
private val VIRTUAL_PREFIXES =
  listOf("bridge", "docker", "veth", "virbr", "vmnet", "vboxnet", "awdl", "llw", "anpi")
private val WIFI_LABELS = listOf("wi-fi", "wifi", "wireless", "wlan", "802.11")
private val CELLULAR_PREFIXES = listOf("wwan", "wwp", "rmnet")
private val CELLULAR_LABELS = listOf("mobile broadband", "cellular")
private val ETHERNET_PREFIXES = listOf("eth", "enp", "eno", "ens", "enx")
