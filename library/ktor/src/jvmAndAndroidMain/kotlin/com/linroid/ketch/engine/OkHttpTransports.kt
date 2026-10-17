package com.linroid.ketch.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/** Ties the sockets and name lookups of a client to one network, or to none. */
internal interface SocketBinding {
  /** Creates the sockets of direct and HTTP proxy connections. */
  val socketFactory: SocketFactory

  /** Prepares an unconnected [socket], such as binding it to a local address. */
  fun prepare(socket: Socket)

  /** The addresses of [host] on this network. */
  fun lookup(host: String): List<InetAddress>

  /** The system's default routing and resolver. */
  object None : SocketBinding {
    override val socketFactory: SocketFactory = SocketFactory.getDefault()

    override fun prepare(socket: Socket) {}

    override fun lookup(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host)
  }
}

/**
 * An OkHttp client whose requests take [route], [ProxyRoute.Direct] or a proxy, over [binding].
 * Proxy credentials go only to the proxy: with `CONNECT` for HTTPS and with plain HTTP requests,
 * which the proxy forwards, and in the SOCKS5 handshake.
 *
 * @param http1Only whether to keep to HTTP/1.1, so that each segment has a connection of its own
 */
internal fun okHttpClient(
  route: ProxyRoute,
  binding: SocketBinding,
  http1Only: Boolean,
): HttpClient = okHttpClient {
  connectionPool(ConnectionPool())
  if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
  when (route) {
    is ProxyRoute.Http -> {
      proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(route.host, route.port)))
      socketFactory(binding.socketFactory)
      dns(Dns { binding.lookup(it) })
      route.credentials?.let { credentials ->
        val authorization = Credentials.basic(
          credentials.username, credentials.password, Charsets.UTF_8,
        )
        // CONNECT tunnels for HTTPS: OkHttp asks before connecting and after a 407.
        proxyAuthenticator { _, response ->
          response.request.takeIf { it.header(PROXY_AUTHORIZATION) == null }
            ?.newBuilder()?.header(PROXY_AUTHORIZATION, authorization)?.build()
        }
        // Plain HTTP requests go to the proxy itself, which reads and removes the header.
        addNetworkInterceptor { chain ->
          val request = chain.request()
          if (request.isHttps || request.header(PROXY_AUTHORIZATION) != null) {
            chain.proceed(request)
          } else {
            val authorized = request.newBuilder().header(PROXY_AUTHORIZATION, authorization)
            chain.proceed(authorized.build())
          }
        }
      }
    }
    is ProxyRoute.Socks5 -> {
      proxy(Proxy.NO_PROXY)
      socketFactory(Socks5SocketFactory(route, binding))
      // The proxy resolves server names, so they reach the socket unresolved.
      dns(Dns { host -> listOf(InetAddress.getByAddress(host, Socks5Socket.UNRESOLVED)) })
    }
    ProxyRoute.Direct, ProxyRoute.Platform -> {
      proxy(Proxy.NO_PROXY)
      socketFactory(binding.socketFactory)
      dns(Dns { binding.lookup(it) })
    }
  }
}

/**
 * An OkHttp client as [KtorHttpEngine] builds each of its own: on [httpDispatcher], with the
 * limits of [callDispatcher], redirects left to the engine and only a connect timeout; [configure]
 * adds the rest, such as the route.
 */
internal fun okHttpClient(configure: OkHttpClient.Builder.() -> Unit = {}): HttpClient =
  HttpClient(OkHttp) {
    followRedirects = false
    engine {
      dispatcher = httpDispatcher
      config {
        dispatcher(callDispatcher())
        configure()
      }
    }
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }

/**
 * OkHttp's call dispatcher, which counts a request from its start until its response headers
 * arrive, holding back those past its limits. Its defaults, 64 at once and 5 per host, made a
 * download's sixth segment request wait for an earlier one's headers, and announces wait behind
 * those to trackers that never answer. Ketch chooses how many connections it opens, per download
 * and per host, so both limits are [MAX_CALLS]. Each client gets its own, as closing a client
 * shuts its dispatcher's threads down.
 */
private fun callDispatcher(): Dispatcher = Dispatcher().apply {
  maxRequests = MAX_CALLS
  maxRequestsPerHost = MAX_CALLS
}

/** As many requests as response bodies stream at once ([httpDispatcher]). */
private const val MAX_CALLS = 256

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
