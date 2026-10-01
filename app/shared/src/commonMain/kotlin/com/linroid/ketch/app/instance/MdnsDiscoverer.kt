package com.linroid.ketch.app.instance

interface MdnsDiscoverer {
  /** Whether this platform can browse the local network; `false` in the browser. */
  val supported: Boolean get() = true

  suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer>
}

internal object NoOpMdnsDiscoverer : MdnsDiscoverer {
  override val supported: Boolean get() = false

  override suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer> = emptyList()
}

internal expect fun createMdnsDiscoverer(): MdnsDiscoverer
