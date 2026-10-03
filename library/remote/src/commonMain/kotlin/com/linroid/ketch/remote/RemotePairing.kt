package com.linroid.ketch.remote

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.PairingRequest
import com.linroid.ketch.endpoints.model.PairingState
import com.linroid.ketch.endpoints.model.PairingStatus
import com.linroid.ketch.endpoints.model.PairingTicket
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Asks the owner of a Ketch server for its access token, instead of having someone type it. The
 * server shows the request, with the code this device shows too, until its owner answers.
 *
 * Servers that take requests advertise `pairing=1` over mDNS; others answer
 * [PairingResult.Unsupported].
 *
 * @param host server hostname or IP address
 * @param port server port (default 8642)
 * @param secure use HTTPS instead of HTTP
 */
class RemotePairing internal constructor(
  val host: String,
  val port: Int,
  val secure: Boolean,
  engine: HttpClientEngine?,
  private val pollInterval: Duration,
) : AutoCloseable {
  constructor(
    host: String,
    port: Int = 8642,
    secure: Boolean = false,
  ) : this(host, port, secure, null, POLL_INTERVAL)

  private val log = KetchLogger("RemotePairing")
  private val baseUrl = "${if (secure) "https" else "http"}://$host:$port"

  private val configureClient: HttpClientConfig<*>.() -> Unit = {
    install(HttpTimeout) {
      requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
    }
    install(ContentNegotiation) {
      json(Json { ignoreUnknownKeys = true })
    }
    install(Resources)
    defaultRequest { url(baseUrl) }
  }

  private val httpClient = if (engine == null) HttpClient(configureClient)
    else HttpClient(engine, configureClient)

  /**
   * Sends [request] and waits until the server's owner answers or the request expires.
   * Cancelling the call withdraws the request, so the owner is no longer asked.
   *
   * @throws RemoteApiException when the server answers with an unexpected error
   * @throws Exception when the server cannot be reached
   */
  suspend fun request(request: PairingRequest): PairingResult {
    val response = httpClient.post(Api.Pairing()) {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    when (response.status) {
      HttpStatusCode.Accepted -> Unit
      HttpStatusCode.TooManyRequests -> return PairingResult.Busy
      HttpStatusCode.NotFound,
      HttpStatusCode.MethodNotAllowed,
      HttpStatusCode.NotImplemented -> return PairingResult.Unsupported
      else -> fail(response)
    }
    val ticket = response.body<PairingTicket>()
    log.i { "Asked $host:$port to pair; waiting for its owner" }
    try {
      return awaitAnswer(ticket.id)
    } catch (e: CancellationException) {
      withContext(NonCancellable) { withdraw(ticket.id) }
      throw e
    }
  }

  private suspend fun awaitAnswer(id: String): PairingResult {
    while (true) {
      delay(pollInterval)
      val response = httpClient.get(Api.Pairing.ById(id = id))
      if (response.status == HttpStatusCode.NotFound) return PairingResult.Expired
      if (!response.status.isSuccess()) fail(response)
      val status = response.body<PairingStatus>()
      when (status.state) {
        PairingState.PENDING -> Unit
        PairingState.ALLOWED -> {
          val token = status.token ?: throw IllegalStateException("Allowed without a token")
          log.i { "$host:$port allowed pairing" }
          return PairingResult.Allowed(token)
        }
        PairingState.DENIED -> return PairingResult.Denied
        PairingState.EXPIRED -> return PairingResult.Expired
      }
    }
  }

  private suspend fun withdraw(id: String) {
    try {
      httpClient.delete(Api.Pairing.ById(id = id))
    } catch (e: Exception) {
      log.d { "Couldn't withdraw the pairing request: ${e.describeCauses()}" }
    }
  }

  private suspend fun fail(response: HttpResponse): Nothing {
    val error = response.toRemoteApiException()
    log.w { "Pairing failed: HTTP ${error.status}: ${error.errorCode ?: "no error code"}" }
    throw error
  }

  override fun close() {
    httpClient.close()
  }

  private companion object {
    val POLL_INTERVAL: Duration = 1.seconds
    val REQUEST_TIMEOUT: Duration = 15.seconds
  }
}

/** How the owner of a server answered a [RemotePairing.request]. */
sealed interface PairingResult {
  /** The owner let this device in; connect with [token]. */
  data class Allowed(val token: String) : PairingResult

  /** The owner turned this device away. */
  data object Denied : PairingResult

  /** Nobody answered in time. */
  data object Expired : PairingResult

  /**
   * The server takes no requests: it has no access token, nobody can answer there, as with
   * `ketch server`, or it predates pairing. Ask for its access code instead.
   */
  data object Unsupported : PairingResult

  /** Another request from this device, or too many in all, wait for the owner. */
  data object Busy : PairingResult
}
