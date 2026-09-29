package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.server.KetchServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The API server the browser extension reaches this app through, separate from the one users
 * turn on in Settings: it only listens on 127.0.0.1, on a port the system picks, requires a token
 * made for this run, and starts the first time the extension asks for it through
 * [NativeMessagingHost].
 */
internal class BrowserExtensionServer(
  private val startServer: (api: KetchApi, token: String) -> Started = ::startKetchServer,
) : AutoCloseable {
  private val api = CompletableFuture<KetchApi>()
  private var started: Started? = null

  /** A running server: where it listens and how to stop it. */
  class Started(val port: Int, val token: String, val stop: () -> Unit)

  /** Makes [ketch] available; [connect] waits for it while the app starts. */
  fun attach(ketch: KetchApi) {
    api.complete(ketch)
  }

  /**
   * Returns the reply for the extension: the server's address and token as JSON, starting the
   * server first if needed, or an error reply.
   */
  @Synchronized
  fun connect(timeout: Duration = 20.seconds): String {
    val server = started ?: run {
      val ketch = try {
        api.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        return errorReply("not_ready", "Ketch is still starting")
      }
      try {
        startServer(ketch, newToken())
      } catch (e: Exception) {
        return errorReply("server_failed", "Couldn't start the connection: ${e.message}")
      }.also { started = it }
    }
    return buildJsonObject {
      put("url", "http://127.0.0.1:${server.port}")
      put("token", server.token)
    }.toString()
  }

  @Synchronized
  override fun close() {
    started?.stop?.invoke()
    started = null
  }
}

private fun newToken(): String =
  ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

private fun startKetchServer(api: KetchApi, token: String): BrowserExtensionServer.Started {
  val server = KetchServer(
    ketch = api,
    host = "127.0.0.1",
    port = 0,
    apiToken = token,
    mdnsEnabled = false,
  )
  server.start(wait = false)
  return BrowserExtensionServer.Started(runBlocking { server.port() }, token, server::stop)
}
