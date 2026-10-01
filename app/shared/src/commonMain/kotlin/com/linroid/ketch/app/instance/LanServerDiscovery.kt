package com.linroid.ketch.app.instance

import com.linroid.ketch.config.ServerConfig

/**
 * A Ketch server announced on the local network.
 *
 * @property name the name it announces, which the app adopts as the device's name unless it is
 *   the generic one (see [deviceNameOrNull]).
 */
data class DiscoveredServer(
  val name: String,
  val host: String,
  val port: Int,
  val tokenRequired: Boolean,
)

class LanServerDiscovery(
  private val discoverer: MdnsDiscoverer = createMdnsDiscoverer(),
) {
  /** Whether this platform can find servers on the network; "Find on network" hides without. */
  val supported: Boolean get() = discoverer.supported

  @Suppress("UNUSED_PARAMETER")
  suspend fun discover(port: Int = DEFAULT_PORT): List<DiscoveredServer> {
    return discoverer.discover(ServerConfig.MDNS_SERVICE_TYPE, DISCOVERY_TIMEOUT_MS)
      .distinctBy { "${it.host}:${it.port}" }
      .sortedBy { it.host }
  }

  companion object {
    private const val DEFAULT_PORT = 8642
    private const val DISCOVERY_TIMEOUT_MS = 10000L
  }
}
