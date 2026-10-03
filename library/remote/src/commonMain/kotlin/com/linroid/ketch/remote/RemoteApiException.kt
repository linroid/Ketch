package com.linroid.ketch.remote

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * A request that a Ketch server answered with an error status.
 *
 * Servers explain most refusals with an error code and a message in English, such as why a
 * destination was refused. [message] is that message, or the HTTP status when the response did
 * not explain itself (an older server, or a proxy in between).
 *
 * @property status the HTTP status code, such as 403
 * @property errorCode the server's error code, `null` when the response carried none. Among
 *   them: `path_rejected` (403) for a destination, or files to delete, outside the folders the
 *   server allows; `payload_too_large` (413); `unsupported_media_type` (415); and
 *   `too_many_attempts` (429) once this address sent too many wrong access tokens
 * @property retryAfterSeconds how long the server asks to wait before trying again, from its
 *   `Retry-After` header, when it sent one
 */
class RemoteApiException(
  val status: Int,
  val errorCode: String?,
  message: String,
  val retryAfterSeconds: Long? = null,
) : IllegalStateException(message)

private val log = KetchLogger("RemoteKetch")

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * The failure this unsuccessful response reports, with the error code and message of its
 * [ErrorResponse] body when it has one. Reads the body.
 */
internal suspend fun HttpResponse.toRemoteApiException(): RemoteApiException {
  val body = errorResponse()
  return RemoteApiException(
    status = status.value,
    errorCode = body?.error,
    message = body?.message?.ifBlank { null } ?: "HTTP ${status.value}: ${status.description}",
    retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
  )
}

private suspend fun HttpResponse.errorResponse(): ErrorResponse? {
  val text = try {
    bodyAsText()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.d { "Couldn't read the body of HTTP ${status.value}: ${e.describeCauses()}" }
    return null
  }
  if (text.isBlank()) return null
  return try {
    errorJson.decodeFromString<ErrorResponse>(text)
  } catch (e: IllegalArgumentException) {
    // Not a Ketch server's error, such as a proxy's HTML page.
    log.d { "HTTP ${status.value} has no error response: ${e.describeCauses()}" }
    null
  }
}
