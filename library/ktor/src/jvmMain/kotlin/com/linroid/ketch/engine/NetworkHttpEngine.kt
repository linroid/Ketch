package com.linroid.ketch.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import okhttp3.ConnectionPool
import okhttp3.Dns

/**
 * Creates an HTTP engine whose TCP connections bind to [localAddress].
 *
 * Select an address assigned to the desired [NetworkInterface]. Each engine owns a separate
 * connection pool. DNS uses the system resolver, filtered to the address family of [localAddress].
 * System proxies are disabled so that sockets connect directly to the destination.
 * OS routing must support the selected source address; this is source-address binding, not a
 * platform-specific force-interface socket option. A removed address fails rather than falling
 * back to an unbound socket. Recreate the engine when the interface's address changes.
 *
 * Combine several engines with [com.linroid.ketch.core.engine.MultiNetworkHttpEngine] to
 * distribute HTTP segments across interfaces. This factory uses Ktor's OkHttp transport.
 *
 * @param localAddress a concrete IP address assigned to a local interface
 * @param logRequests whether request and transport details may be logged
 * @throws IllegalArgumentException if the address is not a local unicast address
 */
fun KtorHttpEngine.Companion.forLocalAddress(
  localAddress: InetAddress,
  logRequests: Boolean = true,
): KtorHttpEngine {
  require(!localAddress.isAnyLocalAddress && !localAddress.isMulticastAddress) {
    "A concrete local unicast address is required"
  }
  require(NetworkInterface.getByInetAddress(localAddress) != null) {
    "The selected address is not assigned to a local network interface"
  }
  val client = HttpClient(OkHttp) {
    engine {
      config {
        socketFactory(LocalAddressSocketFactory(localAddress))
        connectionPool(ConnectionPool())
        proxy(Proxy.NO_PROXY)
        dns { hostname ->
          Dns.SYSTEM.lookup(hostname).filter { it.address.size == localAddress.address.size }
        }
      }
    }
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }
  return KtorHttpEngine(client, logRequests)
}
