package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyAddress
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How one request reaches its server. */
internal sealed interface ProxyRoute {
  /** Straight to the server. */
  data object Direct : ProxyRoute

  /** Through whatever proxy the platform's own transport chooses, such as iOS's system proxy. */
  data object Platform : ProxyRoute

  /** Through the HTTP proxy at [host]:[port], tunneling HTTPS with `CONNECT`. */
  data class Http(
    val host: String,
    val port: Int,
    val credentials: ProxyCredentials? = null,
  ) : ProxyRoute {
    override fun toString(): String = "http://${printedHost(host)}:$port"
  }

  /** Through the SOCKS5 proxy at [host]:[port], which also resolves the server's name. */
  data class Socks5(
    val host: String,
    val port: Int,
    val credentials: ProxyCredentials? = null,
  ) : ProxyRoute {
    override fun toString(): String = "socks5://${printedHost(host)}:$port"
  }

  companion object {
    /** The route to [address], with [credentials]. */
    fun of(address: ProxyAddress, credentials: ProxyCredentials?): ProxyRoute =
      when (address.type) {
        ProxyAddress.Type.HTTP -> Http(address.host, address.port, credentials)
        ProxyAddress.Type.SOCKS5 -> Socks5(address.host, address.port, credentials)
      }

    private fun printedHost(host: String): String = if (':' in host) "[$host]" else host
  }
}

/** Credentials for a proxy; [toString] leaves [password] out. */
internal class ProxyCredentials(val username: String, val password: String) {
  override fun equals(other: Any?): Boolean =
    other is ProxyCredentials && other.username == username && other.password == password

  override fun hashCode(): Int = username.hashCode() * 31 + password.hashCode()

  override fun toString(): String = "ProxyCredentials(username=$username)"
}

/** The credentials of this configuration, or `null` without a username. */
internal val ProxyConfig.credentials: ProxyCredentials?
  get() = username?.let { ProxyCredentials(it, password.orEmpty()) }

/**
 * The route of a request to [url] under this configuration. [system] decides for
 * [ProxyMode.SYSTEM], after loopback hosts, which always go directly.
 */
internal fun ProxyConfig.routeFor(url: Url, system: (Url) -> ProxyRoute): ProxyRoute {
  if (ProxyConfig.System.bypasses(url.host)) return ProxyRoute.Direct
  return when (mode) {
    ProxyMode.DIRECT -> ProxyRoute.Direct
    ProxyMode.SYSTEM -> system(url)
    ProxyMode.MANUAL -> if (bypasses(url.host)) {
      ProxyRoute.Direct
    } else {
      ProxyRoute.of(checkNotNull(address), credentials)
    }
  }
}

/**
 * The HTTP clients of a [KtorHttpEngine], one per way of reaching servers, each with redirects
 * turned off: the engine follows them itself.
 */
internal interface HttpTransports {
  /** Whether requests can go as [proxy] says. */
  fun supports(proxy: ProxyConfig): Boolean

  /**
   * The client for a request to [url] under [proxy], and the route it takes.
   *
   * @throws com.linroid.ketch.api.KetchError when the route cannot be used, such as an
   *   unsupported proxy in the environment
   */
  fun clientFor(url: Url, proxy: ProxyConfig): RoutedClient

  /** Closes every client. */
  fun close()
}

/** A [client] and the [route] its requests take. */
internal class RoutedClient(val client: HttpClient, val route: ProxyRoute)

/** One client supplied by the caller, used as it is, which only follows the system proxy. */
internal class FixedTransports(private val client: HttpClient) : HttpTransports {
  private val requests = client.config { followRedirects = false }

  override fun supports(proxy: ProxyConfig): Boolean = proxy.mode == ProxyMode.SYSTEM

  override fun clientFor(url: Url, proxy: ProxyConfig): RoutedClient =
    RoutedClient(requests, ProxyRoute.Platform)

  override fun close() {
    requests.close()
    client.close()
  }
}

/** Clients created on first use, one per key, closed together. */
@OptIn(ExperimentalAtomicApi::class)
internal class ClientCache<K : Any> {
  // null once closed.
  private val clients = AtomicReference<Map<K, HttpClient>?>(emptyMap())

  /** The client for [key], built by [create] the first time it is asked for. */
  fun get(key: K, create: () -> HttpClient): HttpClient {
    while (true) {
      val current = checkNotNull(clients.load()) { "HTTP engine is closed" }
      current[key]?.let { return it }
      val created = create()
      if (clients.compareAndSet(current, current + (key to created))) return created
      created.close()
    }
  }

  fun close() {
    clients.exchange(null)?.values?.forEach { it.close() }
  }
}
