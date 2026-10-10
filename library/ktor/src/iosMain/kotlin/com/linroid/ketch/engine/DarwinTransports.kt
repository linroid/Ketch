package com.linroid.ketch.engine

import com.linroid.ketch.api.ProxyConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStreamPropertySOCKSPassword
import platform.CoreFoundation.kCFStreamPropertySOCKSUser
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialWithUser

internal actual fun defaultTransports(): HttpTransports = DarwinTransports()

/**
 * iOS's transports: [com.linroid.ketch.api.ProxyMode.SYSTEM] leaves the proxy to
 * `NSURLSession`, which follows the system's settings; other modes set a session's proxy
 * dictionary, empty for direct requests.
 */
internal class DarwinTransports : HttpTransports {
  private val clients = ClientCache<ProxyRoute>()

  override fun supports(proxy: ProxyConfig): Boolean = true

  override fun clientFor(url: Url, proxy: ProxyConfig): RoutedClient {
    val route = proxy.routeFor(url) { ProxyRoute.Platform }
    return RoutedClient(clients.get(route) { client(route) }, route)
  }

  override fun close() {
    clients.close()
  }

  private fun client(route: ProxyRoute): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    engine { configure(route) }
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun DarwinClientEngineConfig.configure(route: ProxyRoute) {
    when (route) {
      ProxyRoute.Platform -> Unit
      ProxyRoute.Direct -> configureSession { connectionProxyDictionary = emptyMap<Any?, Any?>() }
      is ProxyRoute.Http -> {
        configureSession {
          connectionProxyDictionary = mapOf<Any?, Any?>(
            "HTTPEnable" to 1,
            "HTTPProxy" to route.host,
            "HTTPPort" to route.port,
            "HTTPSEnable" to 1,
            "HTTPSProxy" to route.host,
            "HTTPSPort" to route.port,
          )
        }
        route.credentials?.let { credentials ->
          // Answers the proxy's challenges only; servers' go on as they would without this.
          handleChallenge { _, _, challenge, completionHandler ->
            if (challenge.protectionSpace.isProxy() && challenge.previousFailureCount == 0L) {
              val credential = NSURLCredential.credentialWithUser(
                credentials.username,
                credentials.password,
                NSURLCredentialPersistence.NSURLCredentialPersistenceForSession,
              )
              completionHandler(NSURLSessionAuthChallengeUseCredential.convert(), credential)
            } else {
              completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
            }
          }
        }
      }
      is ProxyRoute.Socks5 -> configureSession {
        val entries = mutableMapOf<Any?, Any?>(
          "SOCKSEnable" to 1,
          "SOCKSProxy" to route.host,
          "SOCKSPort" to route.port,
        )
        route.credentials?.let {
          entries[key(kCFStreamPropertySOCKSUser)] = it.username
          entries[key(kCFStreamPropertySOCKSPassword)] = it.password
        }
        connectionProxyDictionary = entries
      }
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun key(constant: CFStringRef?): String =
    CFBridgingRelease(CFRetain(constant)) as String
}
