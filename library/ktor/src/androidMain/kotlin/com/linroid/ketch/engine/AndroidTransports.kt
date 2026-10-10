package com.linroid.ketch.engine

import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import com.linroid.ketch.api.ProxyConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

internal actual fun defaultTransports(): HttpTransports = AndroidTransports(network = null)

/**
 * Android's transports, bound to [network] when it is set.
 *
 * Unbound, [com.linroid.ketch.api.ProxyMode.SYSTEM] leaves the proxy to OkHttp, which follows
 * the active network's. Bound to [network], it uses that network's own HTTP proxy from
 * [connectivity], or none when the network has a proxy auto-configuration script, whose local
 * proxy the network cannot reach.
 */
internal class AndroidTransports(
  private val network: Network?,
  private val connectivity: ConnectivityManager? = null,
) : HttpTransports {
  private val binding = network?.let(::NetworkBinding) ?: SocketBinding.None
  private val clients = ClientCache<ProxyRoute>()

  override fun supports(proxy: ProxyConfig): Boolean = true

  override fun clientFor(url: Url, proxy: ProxyConfig): RoutedClient {
    val route = proxy.routeFor(url) {
      if (network == null) ProxyRoute.Platform else networkRoute(network, it)
    }
    val client = clients.get(route) {
      // OkHttp's own protocols, as before proxies could be chosen.
      if (route == ProxyRoute.Platform) {
        platformClient()
      } else {
        okHttpClient(route, binding, http1Only = false)
      }
    }
    return RoutedClient(client, route)
  }

  override fun close() {
    clients.close()
  }

  private fun networkRoute(network: Network, url: Url): ProxyRoute {
    val info = connectivity?.getLinkProperties(network)?.httpProxy ?: return ProxyRoute.Direct
    val host = info.host
    if (info.pacFileUrl != Uri.EMPTY || host.isNullOrEmpty() || info.port <= 0) {
      return ProxyRoute.Direct
    }
    val bypass = ProxyConfig(bypass = info.exclusionList.filter(ProxyConfig::isValidBypass))
    return if (bypass.bypasses(url.host)) ProxyRoute.Direct else ProxyRoute.Http(host, info.port)
  }

  private fun platformClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }
}

/** Binds sockets and name lookups to [network]. */
private class NetworkBinding(private val network: Network) : SocketBinding {
  override val socketFactory: SocketFactory = network.socketFactory

  override fun prepare(socket: Socket) {
    network.bindSocket(socket)
  }

  override fun lookup(host: String): List<InetAddress> = network.getAllByName(host).toList()
}
