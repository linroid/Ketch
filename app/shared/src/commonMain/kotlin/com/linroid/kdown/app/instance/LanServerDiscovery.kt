package com.linroid.kdown.app.instance

import com.linroid.kdown.api.config.ServerConfig

data class DiscoveredServer(
  val name: String,
  val host: String,
  val port: Int,
  val tokenRequired: Boolean,
)

class LanServerDiscovery(
  private val discoverer: MdnsDiscoverer = createMdnsDiscoverer(),
) {
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