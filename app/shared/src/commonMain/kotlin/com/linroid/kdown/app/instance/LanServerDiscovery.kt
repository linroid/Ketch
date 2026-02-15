package com.linroid.kdown.app.instance

data class DiscoveredServer(
  val name: String,
  val host: String,
  val port: Int,
  val tokenRequired: Boolean,
)

internal expect suspend fun discoverLanServicesViaMdns(
  timeoutMs: Long,
): List<DiscoveredServer>

class LanServerDiscovery {
  @Suppress("UNUSED_PARAMETER")
  suspend fun discover(port: Int = DEFAULT_PORT): List<DiscoveredServer> {
    return discoverLanServicesViaMdns(timeoutMs = DISCOVERY_TIMEOUT_MS)
      .distinctBy { "${it.host}:${it.port}" }
      .sortedBy { it.host }
  }

  companion object {
    private const val DEFAULT_PORT = 8642
    private const val DISCOVERY_TIMEOUT_MS = 10000L
  }
}
