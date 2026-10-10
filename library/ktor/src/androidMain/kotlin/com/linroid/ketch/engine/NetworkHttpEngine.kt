package com.linroid.ketch.engine

import android.net.ConnectivityManager
import android.net.Network

/**
 * Creates an HTTP engine with sockets and DNS bound to an Android [network].
 *
 * Obtain and keep the network available with ConnectivityManager. The caller owns any network
 * callbacks and must unregister them when no longer needed. Losing the network causes requests
 * to fail; the engine never silently switches to the system default network. Each engine owns
 * separate connection pools. Proxies ([KtorHttpEngine.withProxy]) are reached over the network
 * too; the system's is the network's own HTTP proxy, read from [connectivityManager], and without
 * one requests connect directly on the chosen network. Combine Wi-Fi and cellular engines with
 * [com.linroid.ketch.core.engine.MultiNetworkHttpEngine] to distribute segments; cellular
 * transfers may incur charges.
 *
 * @param network an available network chosen by the caller
 * @param logRequests whether request and transport details may be logged
 * @param userAgent the `User-Agent` of requests whose headers name none, or `null` for none
 * @param connectivityManager reads the network's proxy for
 *   [com.linroid.ketch.api.ProxyMode.SYSTEM]; `null` to connect directly instead
 */
fun KtorHttpEngine.Companion.forNetwork(
  network: Network,
  logRequests: Boolean = true,
  userAgent: String? = KtorHttpEngine.DEFAULT_USER_AGENT,
  connectivityManager: ConnectivityManager? = null,
): KtorHttpEngine =
  KtorHttpEngine(AndroidTransports(network, connectivityManager), logRequests, userAgent)
