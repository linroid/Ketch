package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.ConnectionEvents
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * The live connections stream of a server, `GET /api/connections/events`: each snapshot it
 * sends, without the repeats it sends while nothing changes. The flow fails with
 * [UnsupportedOperationException] when the server has no such stream or reports no connections,
 * with [RemoteApiException] for its other errors, and with [IllegalStateException] when the
 * stream ends without saying why, as when the server stops. It never reconnects by itself.
 */
internal fun HttpClient.activeConnectionEvents(limit: Int, json: Json): Flow<ActiveConnections> =
  flow {
    // Engines such as Darwin's run execute's block on another dispatcher, where a plain flow may
    // not emit; the stream is read in a channel flow and its outcome is replayed here in order,
    // so a snapshot read just before a failure is still delivered.
    readInChannel(limit, json).collect { event ->
      when (event) {
        is StreamEvent.Update -> emit(event.snapshot)
        is StreamEvent.Failed -> throw event.error
      }
    }
  }

private sealed interface StreamEvent {
  class Update(val snapshot: ActiveConnections) : StreamEvent
  class Failed(val error: Throwable) : StreamEvent
}

private fun HttpClient.readInChannel(limit: Int, json: Json): Flow<StreamEvent> = channelFlow {
  try {
    readEvents(limit, json) { send(StreamEvent.Update(it)) }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    send(StreamEvent.Failed(e))
  }
}

private suspend fun HttpClient.readEvents(
  limit: Int,
  json: Json,
  deliver: suspend (ActiveConnections) -> Unit,
) {
  val resource = Api.ActiveConnections.Events(Api.ActiveConnections(limit = limit))
  // Read as plain text: the stream is simple, and any engine can serve it.
  prepareGet(resource) {
    header(HttpHeaders.Accept, "text/event-stream")
  }.execute { response ->
    if (!response.status.isSuccess()) throw response.streamRefused()
    val channel = response.bodyAsChannel()
    var name: String? = null
    var last: String? = null
    val data = StringBuilder()
    while (true) {
      val line = channel.readUTF8Line()
      // A blank line ends an event; so does the end of the stream, leniently.
      if (line.isNullOrEmpty()) {
        val event = name
        name = null
        val text = data.toString()
        data.clear()
        when (event) {
          ConnectionEvents.SNAPSHOT -> if (text != last) {
            last = text
            deliver(json.decodeFromString<ActiveConnections>(text))
          }
          ConnectionEvents.ERROR -> throw json.decodeFromString<ErrorResponse>(text).toFailure()
        }
        if (line == null) break
        continue
      }
      if (line.startsWith(":")) continue
      val value = line.substringAfter(':', "").removePrefix(" ")
      when (line.substringBefore(':')) {
        "event" -> name = value
        "data" -> {
          if (data.isNotEmpty()) data.append('\n')
          data.append(value)
        }
      }
    }
  }
  throw IllegalStateException("The server closed the connections stream")
}

/** The failure of a stream the server refused: unsupported when it has no such route. */
private suspend fun HttpResponse.streamRefused(): Exception {
  val body = errorResponse()
  val missingRoute = body?.error == null &&
    (status == HttpStatusCode.NotFound || status == HttpStatusCode.MethodNotAllowed)
  return when {
    missingRoute || status == HttpStatusCode.NotImplemented ||
      body?.error == ConnectionEvents.UNSUPPORTED ->
      UnsupportedOperationException(body?.message ?: "This device does not report connections")
    body?.error == ConnectionEvents.INVALID_LIMIT ->
      IllegalArgumentException(body.message)
    else -> toRemoteApiException(body)
  }
}

/** The failure an `error` event reports, with the status the same refusal answers before. */
private fun ErrorResponse.toFailure(): Exception = when (error) {
  ConnectionEvents.UNSUPPORTED -> UnsupportedOperationException(message)
  ConnectionEvents.TOO_MANY_STREAMS -> RemoteApiException(
    HttpStatusCode.TooManyRequests.value,
    error,
    message,
  )
  else -> RemoteApiException(HttpStatusCode.InternalServerError.value, error, message)
}
