package com.linroid.ketch.server.api

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NoDownloadSourceException
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.api.resolve
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.server.DestinationGuard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val log = KetchLogger("ServerRoutes")

/**
 * Installs server-level status, configuration, network interface, and URL resolution endpoints.
 * A new download directory goes through [destinations].
 */
internal fun Route.serverRoutes(ketch: KetchApi, destinations: DestinationGuard) {
  get<Api.Status> {
    log.d { "GET /api/status" }
    val status = ketch.status()
    // The event stream is the server's, whatever the engine behind it.
    call.respond(status.copy(features = status.features + StreamEvents.FEATURE))
  }

  put<Api.Config> {
    val fields = call.receiveJson<JsonObject>()
    // Fields a client leaves out keep their current values: clients older than a setting, such
    // as category folders or idle timeouts, must not reset it.
    val current = ServerJson.encodeToJsonElement(DownloadConfig.serializer(), ketch.status().config)
    val body = ServerJson.decodeFromJsonElement(
      DownloadConfig.serializer(),
      JsonObject(current.jsonObject + fields),
    )
    log.i { "PUT /api/config: speedLimit=${body.speedLimit}" }
    destinations.checkConfig(body)
    ketch.updateConfig(body)
    call.respond(body)
  }

  get<Api.NetworkInterfaces> {
    call.respond(ketch.networkInterfaces())
  }

  put<Api.NetworkInterfaces> {
    val body = call.receiveJson<NetworkInterfaceConfig>()
    try {
      call.respond(ketch.selectNetworkInterfaces(body))
    } catch (e: UnsupportedOperationException) {
      call.respond(
        HttpStatusCode.NotImplemented,
        ErrorResponse("unsupported", e.message ?: "Network interface configuration is unavailable"),
      )
    }
  }

  post<Api.Resolve> {
    val body = call.receiveJson<ResolveUrlRequest>()
    log.i { "POST /api/resolve url=${redactUrl(body.url)}" }
    call.respondResolved("No download source handles this URL") {
      ketch.resolve(body.url, body.properties)
    }
  }.stopWhenClientLeaves()

  post<Api.Resolve.Content> { resource ->
    val content = readBounded(call, MAX_RESOLVE_CONTENT_BYTES)
    if (content == null) {
      call.respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse("payload_too_large", "Content exceeds $MAX_RESOLVE_CONTENT_BYTES bytes"),
      )
      return@post
    }
    log.i { "POST /api/resolve/content fileName=${resource.fileName} size=${content.size}" }
    try {
      call.respondResolved("No download source recognizes this content") {
        ketch.resolve(content, resource.fileName)
      }
    } catch (e: UnsupportedOperationException) {
      call.respond(
        HttpStatusCode.NotImplemented,
        ErrorResponse("unsupported", e.message ?: "Resolving file content is unavailable"),
      )
    }
  }.stopWhenClientLeaves()
}

/**
 * Responds with what [resolve] found. An input no source takes answers 415 `unsupported` with
 * [noSource]; one a source takes but lacks something for, such as byte ranges or a usable proxy,
 * the same with that reason. A source's refusal, such as a torrent with pieces too large, answers
 * 422 with the [KetchError] itself, typed like the errors in task snapshots, so clients keep its
 * reason.
 */
private suspend inline fun ApplicationCall.respondResolved(
  noSource: String,
  resolve: () -> ResolvedSource,
) {
  val resolved = try {
    resolve()
  } catch (e: KetchError.Unsupported) {
    val message = if (e.cause is NoDownloadSourceException) noSource else e.reason()
    respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("unsupported", message))
    return
  } catch (e: KetchError.SourceError) {
    respond(HttpStatusCode.UnprocessableEntity, refusalBody(e))
    return
  }
  respond(resolved)
}

/**
 * [error] as task snapshots carry it, plus the `error` code of an [ErrorResponse]: clients that
 * read every refusal as one, such as releases from before URL refusals were typed, keep its
 * message. Clients that read the [KetchError] ignore the extra key.
 */
private fun refusalBody(error: KetchError.SourceError): JsonObject {
  val typed = ServerJson.encodeToJsonElement(KetchError.serializer(), error).jsonObject
  return JsonObject(typed + ("error" to JsonPrimitive("source_error")))
}

/**
 * What a source that lacks something for an input says: its cause's reason, left out when it
 * quotes a URL, which may carry credentials.
 */
private fun KetchError.Unsupported.reason(): String {
  val reason = cause?.message?.trim()
    ?.takeIf { it.isNotEmpty() && "://" !in it && !it.contains("magnet:", ignoreCase = true) }
  return if (reason == null) "Not supported" else "Not supported: $reason"
}

/** Upper bound for uploaded content ([ResolveInput.Content.MAX_CONTENT_BYTES]). */
internal const val MAX_RESOLVE_CONTENT_BYTES = ResolveInput.Content.MAX_CONTENT_BYTES.toInt()

// A resolve may wait for peers as long as it takes, so it stops when its client disconnects.
private fun Route.stopWhenClientLeaves() {
  install(HttpRequestLifecycle) { cancelCallOnClose = true }
}
