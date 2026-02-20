package com.linroid.kdown.app.instance

interface MdnsDiscoverer {
  suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer>
}

internal object NoOpMdnsDiscoverer : MdnsDiscoverer {
  override suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer> = emptyList()
}

internal expect fun createMdnsDiscoverer(): MdnsDiscoverer