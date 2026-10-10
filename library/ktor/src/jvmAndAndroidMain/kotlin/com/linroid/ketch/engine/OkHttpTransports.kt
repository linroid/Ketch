package com.linroid.ketch.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
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
): HttpClient = HttpClient(OkHttp) {
  followRedirects = false
  engine {
    config {
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
  }
  install(HttpTimeout) {
    socketTimeoutMillis = Long.MAX_VALUE
    requestTimeoutMillis = Long.MAX_VALUE
  }
}

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
