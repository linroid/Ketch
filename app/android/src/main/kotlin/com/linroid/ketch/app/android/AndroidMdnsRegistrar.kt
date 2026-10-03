package com.linroid.ketch.app.android

import com.appstractive.dnssd.NetService
import com.appstractive.dnssd.createNetService
import com.linroid.ketch.server.mdns.MdnsRegistrar

/**
 * Compiles the DNS-SD factory call against Android's NSD implementation. The server's JVM
 * registrar calls NetService_jvmKt, which is absent from the Android DNS-SD artifact.
 */
internal class AndroidMdnsRegistrar : MdnsRegistrar {
  private var service: NetService? = null

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
    unregister()
    val next = createNetService(
      type = serviceType,
      name = serviceName,
      port = port,
      txt = metadata,
    )
    // Retain it before registration so the server can clean up a failed or cancelled attempt.
    service = next
    next.register()
  }

  override suspend fun unregister() {
    val previous = service
    service = null
    previous?.unregister()
  }
}
