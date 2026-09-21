package com.linroid.ketch.engine

import android.net.Network
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.Proxy
import okhttp3.ConnectionPool

/**
 * Creates an HTTP engine with sockets and DNS bound to an Android [network].
 *
 * Obtain and keep the network available with ConnectivityManager. The caller owns any network
 * callbacks and must unregister them when no longer needed. Losing the network causes requests
 * to fail; the engine never silently switches to the system default network. Each engine owns a
 * separate connection pool and disables system proxies to connect directly on the chosen network.
 * Combine Wi-Fi and cellular engines with [com.linroid.ketch.core.engine.MultiNetworkHttpEngine]
 * to distribute segments; cellular transfers may incur charges.
 *
 * @param network an available network chosen by the caller
 * @param logRequests whether request and transport details may be logged
 */
fun KtorHttpEngine.Companion.forNetwork(
  network: Network,
  logRequests: Boolean = true,
): KtorHttpEngine {
  val client = HttpClient(OkHttp) {
    engine {
      config {
        socketFactory(network.socketFactory)
        dns { hostname -> network.getAllByName(hostname).toList() }
        connectionPool(ConnectionPool())
        proxy(Proxy.NO_PROXY)
      }
    }
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }
  return KtorHttpEngine(client, logRequests)
}
