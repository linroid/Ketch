package com.linroid.ketch.server.mdns

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import com.linroid.ketch.api.log.KetchLogger

/**
 * [MdnsRegistrar] implementation using dns-sd-kt (JmDNS on JVM).
 *
 * Works well on Linux and Windows where no system mDNS daemon
 * conflicts with JmDNS. On macOS, prefer [NativeMdnsRegistrar].
 */
internal class DnsSdRegistrar : MdnsRegistrar {
  private val log = KetchLogger("DnsSdRegistrar")
  @Volatile private var service: NetService? = null

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
    log.d {
      "Register: name=$serviceName, type=$serviceType, port=$port"
    }
    unregister()
    service = createNetService(
      type = serviceType,
      name = serviceName,
      port = port,
      txt = metadata,
    )
    service?.register()
  }

  override suspend fun unregister() {
    log.d { "Unregister" }
    service?.let { svc ->
      runCatching { svc.unregister() }.onFailure { e ->
        log.w(e) { "Failed to unregister service" }
      }
    }
    service = null
  }
}
