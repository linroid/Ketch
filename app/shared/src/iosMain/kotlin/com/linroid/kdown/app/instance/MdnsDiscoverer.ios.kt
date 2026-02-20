package com.linroid.kdown.app.instance

import com.appstractive.dnssd.DiscoveryEvent
import com.appstractive.dnssd.discoverServices
import com.appstractive.dnssd.key
import kotlinx.coroutines.withTimeoutOrNull

internal class DnsSdDiscoverer : MdnsDiscoverer {
  override suspend fun discover(
    serviceType: String,
    timeoutMs: Long,
  ): List<DiscoveredServer> {
    val results = mutableMapOf<String, DiscoveredServer>()
    withTimeoutOrNull(timeoutMs) {
      discoverServices(serviceType).collect { event ->
        when (event) {
          is DiscoveryEvent.Discovered -> event.resolve()
          is DiscoveryEvent.Resolved -> {
            val service = event.service
            val host = service.addresses.firstOrNull()
              ?: return@collect
            results[service.key] = DiscoveredServer(
              name = service.name,
              host = host,
              port = service.port,
              tokenRequired = service.txt["token"]
                ?.decodeToString() == "required",
            )
          }
          is DiscoveryEvent.Removed -> {
            results.remove(event.service.key)
          }
        }
      }
    }
    return results.values.toList()
  }
}

internal actual fun createMdnsDiscoverer(): MdnsDiscoverer =
  DnsSdDiscoverer()