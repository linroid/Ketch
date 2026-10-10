package com.linroid.ketch.engine

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Creates an HTTP engine whose TCP connections bind to [localAddress].
 *
 * Select an address assigned to the desired [NetworkInterface]. Each engine owns separate
 * connection pools. DNS uses the system resolver, filtered to the address family of
 * [localAddress]. Proxies ([KtorHttpEngine.withProxy]) are reached from the address too, and the
 * system's are those of the environment and the JVM ([com.linroid.ketch.api.ProxyMode.SYSTEM]).
 * OS routing must support the selected source address; this is source-address binding, not a
 * platform-specific force-interface socket option. A removed address fails rather than falling
 * back to an unbound socket. Recreate the engine when the interface's address changes.
 *
 * Combine several engines with [com.linroid.ketch.core.engine.MultiNetworkHttpEngine] to
 * distribute HTTP segments across interfaces. This factory uses Ktor's OkHttp transport.
 *
 * @param localAddress a concrete IP address assigned to a local interface
 * @param logRequests whether request and transport details may be logged
 * @param userAgent the `User-Agent` of requests whose headers name none, or `null` for none
 * @throws IllegalArgumentException if the address is not a local unicast address
 */
fun KtorHttpEngine.Companion.forLocalAddress(
  localAddress: InetAddress,
  logRequests: Boolean = true,
  userAgent: String? = KtorHttpEngine.DEFAULT_USER_AGENT,
): KtorHttpEngine {
  require(!localAddress.isAnyLocalAddress && !localAddress.isMulticastAddress) {
    "A concrete local unicast address is required"
  }
  require(NetworkInterface.getByInetAddress(localAddress) != null) {
    "The selected address is not assigned to a local network interface"
  }
  return KtorHttpEngine(JvmTransports(localAddress), logRequests, userAgent)
}
