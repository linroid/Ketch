package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ProxyAddress
import com.linroid.ketch.api.ProxyConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import javax.net.SocketFactory

internal actual fun defaultTransports(): HttpTransports = JvmTransports(localAddress = null)

/**
 * The JVM's transports, bound to [localAddress] when it is set.
 *
 * [com.linroid.ketch.api.ProxyMode.SYSTEM] takes the proxy from the environment, then from
 * [selector]. Requests that go directly use CIO, unless [selector] would send them through a
 * proxy, which CIO cannot be told not to use, or the engine is bound to an address; OkHttp
 * carries the rest.
 */
internal class JvmTransports(
  private val localAddress: InetAddress?,
  environment: Map<String, String> = System.getenv(),
  private val selector: () -> ProxySelector? = { ProxySelector.getDefault() },
) : HttpTransports {
  private val environmentProxies = EnvironmentProxies(environment)
  private val binding = localAddress?.let(::LocalAddressBinding) ?: SocketBinding.None
  private val clients = ClientCache<Any>()

  override fun supports(proxy: ProxyConfig): Boolean = true

  override fun clientFor(url: Url, proxy: ProxyConfig): RoutedClient {
    val route = proxy.routeFor(url) { environmentProxies.routeFor(it) ?: selectorRoute(it) }
    val client = when {
      route != ProxyRoute.Direct -> clients.get(route) {
        okHttpClient(route, binding, http1Only = true)
      }
      // Kept as before proxies could be chosen: OkHttp's protocols, bound to the address.
      localAddress != null -> clients.get(route) {
        okHttpClient(route, binding, http1Only = false)
      }
      selectsDirect(url) -> clients.get(CIO_CLIENT) { cioClient() }
      else -> clients.get(route) { okHttpClient(route, binding, http1Only = true) }
    }
    return RoutedClient(client, route)
  }

  override fun close() {
    clients.close()
  }

  /** The proxy [selector] chooses for [url]: the first it lists, if it is HTTP or SOCKS. */
  private fun selectorRoute(url: Url): ProxyRoute {
    val proxy = select(url)?.firstOrNull() ?: return ProxyRoute.Direct
    val address = proxy.address() as? InetSocketAddress ?: return ProxyRoute.Direct
    return when (proxy.type()) {
      Proxy.Type.HTTP -> ProxyRoute.Http(address.hostString, address.port)
      Proxy.Type.SOCKS -> ProxyRoute.Socks5(address.hostString, address.port)
      else -> ProxyRoute.Direct
    }
  }

  /** Whether CIO, which asks [selector] itself, would connect to [url] directly. */
  private fun selectsDirect(url: Url): Boolean =
    select(url)?.all { it.type() == Proxy.Type.DIRECT } != false

  private fun select(url: Url): List<Proxy>? = try {
    val host = url.host.removePrefix("[").removeSuffix("]")
    selector()?.select(URI(url.protocol.name, null, host, url.port, "/", null, null))
  } catch (_: Exception) {
    null
  }

  private fun cioClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }

  private companion object {
    val CIO_CLIENT = Any()
  }
}

/**
 * The proxies the environment names, as curl reads them: `https_proxy` for HTTPS URLs and
 * `http_proxy` for HTTP ones, else `all_proxy`, each in lower case first, then in upper case;
 * `no_proxy` lists the hosts that go directly, separated by commas.
 */
internal class EnvironmentProxies(private val environment: Map<String, String>) {
  private val noProxy = ProxyConfig(
    bypass = variable("no_proxy")?.second.orEmpty()
      .split(',', ' ', '\t').map { it.trim() }
      .filter { it.isNotEmpty() && ProxyConfig.isValidBypass(it) },
  )

  /**
   * The route the environment gives [url], or `null` when it names no proxy for it.
   *
   * @throws KetchError.Unsupported when the proxy it names is not an HTTP or SOCKS5 proxy URL,
   *   rather than connecting without the proxy
   */
  fun routeFor(url: Url): ProxyRoute? {
    val (name, value) = variable("${url.protocol.name.lowercase()}_proxy")
      ?: variable("all_proxy")
      ?: return null
    if (noProxy.bypasses(url.host)) return ProxyRoute.Direct
    val address = ProxyAddress.parse(value) ?: throw KetchError.Unsupported(
      cause = IllegalArgumentException("$name is not an HTTP or SOCKS5 proxy URL"),
    )
    return ProxyRoute.of(
      address.copy(username = null, password = null),
      address.username?.let { ProxyCredentials(it, address.password.orEmpty()) },
    )
  }

  private fun variable(name: String): Pair<String, String>? =
    listOf(name, name.uppercase()).firstNotNullOfOrNull { key ->
      environment[key]?.takeIf { it.isNotBlank() }?.let { key to it }
    }
}

/** Binds sockets to [address] and looks names up in its address family only. */
private class LocalAddressBinding(private val address: InetAddress) : SocketBinding {
  override val socketFactory: SocketFactory = LocalAddressSocketFactory(address)

  override fun prepare(socket: Socket) {
    socket.bind(InetSocketAddress(address, 0))
  }

  override fun lookup(host: String): List<InetAddress> =
    Dns.SYSTEM.lookup(host).filter { it.address.size == address.address.size }
}
