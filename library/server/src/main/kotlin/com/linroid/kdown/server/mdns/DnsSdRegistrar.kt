package com.linroid.kdown.server.mdns

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService

/**
 * [MdnsRegistrar] implementation using dns-sd-kt (JmDNS on JVM).
 *
 * Works well on Linux and Windows where no system mDNS daemon
 * conflicts with JmDNS. On macOS, prefer [NativeMdnsRegistrar].
 */
internal class DnsSdRegistrar : MdnsRegistrar {
  @Volatile private var service: NetService? = null

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
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
    service?.let { svc ->
      runCatching { svc.unregister() }
    }
    service = null
  }
}
