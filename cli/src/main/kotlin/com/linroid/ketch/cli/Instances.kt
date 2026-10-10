package com.linroid.ketch.cli

import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteApiException
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Environment variable naming the server the attaching commands use, like `--server`. */
internal const val SERVER_ENV = "KETCH_SERVER"

/** Port of a Ketch server whose `http` address names none. */
private const val DEFAULT_PORT = 8642

/** A command could not do what it was asked; [message] tells the user why. */
internal class CliFailure(message: String) : Exception(message)

/**
 * A Ketch instance the attaching commands can work on, and how to reach its API.
 *
 * @property label how messages name it, such as "the Ketch app"
 * @property local whether it runs on this machine, so paths mean the same to it as here
 */
internal data class InstanceEndpoint(
  val label: String,
  val host: String,
  val port: Int,
  val secure: Boolean,
  val token: String?,
  val local: Boolean,
)

/**
 * Finds the instance the attaching commands (`add`, `list`, `pause`, `resume`, `watch` and
 * `mcp`) work on, instead of opening the task database with an engine of their own:
 *
 * 1. [server] (`--server` or [SERVER_ENV]), with [token] (`--token` or [TOKEN_ENV])
 * 2. the desktop app running for this user, asked for its loopback API server
 * 3. the `ketch server` or `ketch mcp --standalone` running the downloads of [configDir]
 *
 * @param askApp sends a request to the desktop app ([DesktopApp.request])
 * @param isAlive whether a process with this ID runs
 */
internal class InstanceLocator(
  private val configDir: File,
  private val server: String?,
  private val token: String?,
  private val askApp: (File, String) -> DesktopApp.Reply = { dir, message ->
    DesktopApp.request(dir, message)
  },
  private val isAlive: (Long) -> Boolean = { pid ->
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
  },
  private val appStartTimeout: Duration = 30.seconds,
  private val retryInterval: Duration = 500.milliseconds,
) {

  /**
   * The instance to attach to.
   *
   * @throws CliFailure if none runs, [server] is not an address, or the app cannot be reached
   */
  fun locate(): InstanceEndpoint {
    server?.takeIf { it.isNotBlank() }?.let { return serverEndpoint(it) }
    desktopApp()?.let { return it }
    cliInstance()?.let { return it }
    throw CliFailure(
      "Ketch isn't running. Open the Ketch app or start `ketch server`, " +
        "or pass --server <url> to use another device."
    )
  }

  private fun serverEndpoint(address: String): InstanceEndpoint {
    val endpoint = parseServerAddress(address, token)
    if (endpoint.token != null || !endpoint.local) return endpoint
    // The running `ketch server`'s own token, when the address is its own.
    val info = CliInstance.read(configDir) ?: return endpoint
    val own = info.url?.let { runCatching { parseServerAddress(it, null) }.getOrNull() }
    return if (own != null && own.host == endpoint.host && own.port == endpoint.port) {
      endpoint.copy(token = info.token)
    } else {
      endpoint
    }
  }

  private fun desktopApp(): InstanceEndpoint? {
    val start = TimeSource.Monotonic.markNow()
    while (true) {
      val reply = when (val answer = askApp(configDir, DesktopApp.CONNECT_REQUEST)) {
        DesktopApp.Reply.NotRunning -> return null
        DesktopApp.Reply.Empty -> throw CliFailure(
          "The Ketch app that is running doesn't take commands from the command line yet; " +
            "update it."
        )
        is DesktopApp.Reply.Text -> parseJson(answer.text)
          ?: throw CliFailure("The Ketch app sent a reply the command line doesn't understand")
      }
      val error = reply["error"]?.jsonPrimitive?.contentOrNull
      if (error == null) {
        val url = reply["url"]?.jsonPrimitive?.contentOrNull
          ?: throw CliFailure("The Ketch app sent no address")
        return parseServerAddress(url, reply["token"]?.jsonPrimitive?.contentOrNull)
          .copy(label = "the Ketch app", local = true)
      }
      val message = reply["message"]?.jsonPrimitive?.contentOrNull ?: error
      // The app answers once it has loaded its downloads, and says it is starting until then.
      if (error != NOT_READY || start.elapsedNow() >= appStartTimeout) {
        throw CliFailure("The Ketch app couldn't take the connection: $message")
      }
      Thread.sleep(retryInterval.inWholeMilliseconds)
    }
  }

  private fun cliInstance(): InstanceEndpoint? {
    val info = CliInstance.read(configDir) ?: return null
    val url = info.url ?: return null
    // A process that stopped without cleaning up leaves its info file behind.
    if (!isAlive(info.pid)) return null
    return parseServerAddress(url, info.token).copy(label = info.command, local = true)
  }

  private fun parseJson(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
  } catch (_: IllegalArgumentException) {
    null
  }

  private companion object {
    /** The app's reply while it is still loading (`NativeMessagingHost.NOT_READY`). */
    const val NOT_READY = "not_ready"
  }
}

/**
 * The server at [address]: an `http` or `https` URL, or `host[:port]` for `http`. Without a
 * port, `http` uses Ketch's 8642 and `https` 443, as behind a reverse proxy.
 *
 * @throws CliFailure if [address] is not such an address
 */
internal fun parseServerAddress(address: String, token: String?): InstanceEndpoint {
  val trimmed = address.trim()
  val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
  val uri = try {
    URI(withScheme)
  } catch (_: URISyntaxException) {
    throw CliFailure("'$address' is not a server address, such as http://192.168.1.20:8642")
  }
  val secure = when (uri.scheme?.lowercase()) {
    "http" -> false
    "https" -> true
    else -> throw CliFailure("Use an http:// or https:// server address, not '$address'")
  }
  val host = uri.host
    ?: throw CliFailure("'$address' is not a server address, such as http://192.168.1.20:8642")
  if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/" || uri.rawQuery != null) {
    throw CliFailure("Give the server's address without a path, not '$address'")
  }
  val port = if (uri.port != -1) uri.port else if (secure) 443 else DEFAULT_PORT
  return InstanceEndpoint(
    label = "$host:$port",
    host = host,
    port = port,
    secure = secure,
    token = token?.takeIf { it.isNotBlank() },
    local = isLoopbackHost(host),
  )
}

/** Whether [host] names this machine's loopback interface, without looking it up. */
private fun isLoopbackHost(host: String): Boolean {
  val name = host.removePrefix("[").removeSuffix("]").lowercase()
  if (name == "localhost" || name.endsWith(".localhost")) return true
  // Only IPv4 and IPv6 literals, which InetAddress parses without a DNS lookup.
  val literal = ':' in name || name.isNotEmpty() && name.all { it.isDigit() || it == '.' }
  if (!literal) return false
  return runCatching { InetAddress.getByName(name).isLoopbackAddress }.getOrDefault(false)
}

/** An instance a command is attached to, with the status it reported when it attached. */
internal class Attachment(
  val ketch: RemoteKetch,
  val endpoint: InstanceEndpoint,
  val status: KetchStatus,
) : AutoCloseable {
  override fun close() = ketch.close()
}

/**
 * Connects to [endpoint]. With [syncTasks], it also waits until the instance's tasks are loaded
 * and follows their changes, as `list`, `pause`, `resume` and `watch` need.
 *
 * @throws CliFailure if the instance cannot be reached or refuses the token
 */
internal suspend fun attach(
  endpoint: InstanceEndpoint,
  syncTasks: Boolean = true,
  syncTimeout: Duration = 30.seconds,
): Attachment {
  val ketch = RemoteKetch(endpoint.host, endpoint.port, endpoint.token, endpoint.secure)
  try {
    val status = try {
      ketch.status()
    } catch (e: RemoteApiException) {
      throw refused(endpoint, e)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      throw CliFailure("Couldn't reach ${endpoint.label}: ${e.describeCauses()}")
    }
    if (syncTasks) {
      ketch.start()
      val state = try {
        withTimeout(syncTimeout) {
          ketch.connectionState.first { it !is ConnectionState.Connecting }
        }
      } catch (_: TimeoutCancellationException) {
        null
      }
      when (state) {
        ConnectionState.Connected -> Unit
        ConnectionState.Unauthorized -> throw refused(endpoint, null)
        else -> throw CliFailure("Couldn't load the downloads of ${endpoint.label}")
      }
    }
    return Attachment(ketch, endpoint, status)
  } catch (e: Throwable) {
    ketch.close()
    throw e
  }
}

/** Why [endpoint] refused a request: [error], or the token when that is `null`. */
internal fun refused(endpoint: InstanceEndpoint, error: RemoteApiException?): CliFailure {
  if (error != null && error.status != 401) {
    return CliFailure("${endpoint.label} refused the request: ${error.message}")
  }
  return if (endpoint.token == null) {
    CliFailure(
      "${endpoint.label} requires an access token: pass --token or set $TOKEN_ENV."
    )
  } else {
    CliFailure("${endpoint.label} refused the access token.")
  }
}
