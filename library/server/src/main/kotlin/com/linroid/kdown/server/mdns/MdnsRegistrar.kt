package com.linroid.kdown.server.mdns

/**
 * Interface for mDNS/DNS-SD service registration.
 *
 * Implementations handle advertising the KDown server on the local
 * network so that clients can discover it automatically.
 */
interface MdnsRegistrar {
  /**
   * Registers the service for mDNS discovery.
   *
   * @param serviceType DNS-SD service type (e.g. `"_kdown._tcp"`)
   * @param serviceName human-readable instance name
   * @param port the port the server is listening on
   * @param metadata TXT record key-value pairs
   */
  suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  )

  /** Unregisters the service, removing it from mDNS discovery. */
  suspend fun unregister()
}

/**
 * Returns [NativeMdnsRegistrar] on macOS (where JmDNS conflicts with
 * the system mDNSResponder), [DnsSdRegistrar] on other platforms.
 */
internal fun defaultMdnsRegistrar(): MdnsRegistrar {
  val os = System.getProperty("os.name", "").lowercase()
  return if (os.contains("mac")) NativeMdnsRegistrar()
  else DnsSdRegistrar()
}
