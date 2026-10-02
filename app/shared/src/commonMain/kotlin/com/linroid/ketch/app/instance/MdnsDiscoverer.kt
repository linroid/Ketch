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

/**
 * Which of the [addresses] a found server resolved to the app connects to: an IPv4 one first,
 * since a link-local IPv6 address only works with an interface name, then any other one.
 */
internal fun preferredAddress(addresses: List<String>): String? =
  addresses.firstOrNull(::isIpv4)
    ?: addresses.firstOrNull { !it.startsWith(LINK_LOCAL_PREFIX, ignoreCase = true) }
    ?: addresses.firstOrNull()

private fun isIpv4(address: String): Boolean {
  val parts = address.split('.')
  return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
}

private const val LINK_LOCAL_PREFIX = "fe80:"

