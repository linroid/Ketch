package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
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
 * The API server the browser extension and the `ketch` command line reach this app through,
 * separate from the one users turn on in Settings: it only listens on 127.0.0.1, on a port the
 * system picks, requires a token made for this run, and starts the first time one of them asks
 * for it over [SingleInstance]: the extension through [NativeMessagingHost]
 * ([NativeMessagingHost.CONNECT_REQUEST]), the command line with [CLI_CONNECT_REQUEST].
 *
 * @param onConnect called on the requesting thread each time the extension connects, before it
 *   gets the reply, while the native messaging host that asked for it still runs. A failure there
 *   is logged and the extension still gets its reply.
 */
internal class LocalApiServer(
  private val onConnect: () -> Unit = {},
  private val startServer: (api: KetchApi, token: String) -> Started = ::startKetchServer,
) : AutoCloseable {
  private val log = KetchLogger("LocalApi")
  private val api = CompletableFuture<KetchApi>()
  private var started: Started? = null

  /** A running server: where it listens and how to stop it. */
  class Started(val port: Int, val token: String, val stop: () -> Unit)

  /** Makes [ketch] available; [connect] waits for it while the app starts. */
  fun attach(ketch: KetchApi) {
    api.complete(ketch)
  }

  /**
   * Returns the reply for a client: the server's address and token as JSON, starting the server
   * first if needed, or an error reply.
   *
   * @param fromExtension whether the browser extension asks, which [onConnect] hears of
   */
  @Synchronized
  fun connect(timeout: Duration = 20.seconds, fromExtension: Boolean = true): String {
    val server = started ?: run {
      val ketch = try {
        api.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        return errorReply(NativeMessagingHost.NOT_READY, "Ketch is still starting")
      }
      try {
        startServer(ketch, newToken())
      } catch (e: Exception) {
        return errorReply("server_failed", "Couldn't start the connection: ${e.message}")
      }.also { started = it }
    }
    if (fromExtension) {
      try {
        onConnect()
      } catch (e: Exception) {
        log.w { "Couldn't record the extension's connection: ${e.describeCauses()}" }
      }
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

/**
 * [SingleInstance] request of the `ketch` command line for [LocalApiServer]'s address and token;
 * the command line has its own copy of it.
 */
internal const val CLI_CONNECT_REQUEST = "cli/connect"

private fun newToken(): String =
  ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

private fun startKetchServer(api: KetchApi, token: String): LocalApiServer.Started {
  val server = KetchServer(
    ketch = api,
    host = "127.0.0.1",
    port = 0,
    apiToken = token,
    mdnsEnabled = false,
  )
  server.start(wait = false)
  return LocalApiServer.Started(runBlocking { server.port() }, token, server::stop)
}
