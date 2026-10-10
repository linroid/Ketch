package com.linroid.ketch.server.api

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.server.DestinationGuard
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject

private val log = KetchLogger("ServerRoutes")

/** The [DownloadConfig.categories] field of a `PUT /api/config` body. */
private const val CATEGORIES = "categories"

/**
 * Installs server-level status, configuration, network interface, and URL resolution endpoints.
 * A new download directory goes through [destinations].
 */
internal fun Route.serverRoutes(ketch: KetchApi, destinations: DestinationGuard) {
  get<Api.Status> {
    log.d { "GET /api/status" }
    call.respond(ketch.status())
  }

  put<Api.Config> {
    val fields = call.receiveJson<JsonObject>()
    val sent = ServerJson.decodeFromJsonElement(DownloadConfig.serializer(), fields)
    // Clients older than category folders leave them out, which must not delete them.
    val body = if (CATEGORIES in fields) sent else {
      sent.copy(categories = ketch.status().config.categories)
    }
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
      call.respond(ketch.updateNetworkInterfaces(body))
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
    val resolved = ketch.resolve(body.url, body.properties)
    call.respond(resolved)
  }

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
      call.respond(ketch.resolveContent(content, resource.fileName))
    } catch (e: KetchError.Unsupported) {
      call.respond(
        HttpStatusCode.UnsupportedMediaType,
        ErrorResponse("unsupported", "No download source recognizes this content"),
      )
    } catch (e: KetchError.SourceError) {
      // Typed like the errors in task snapshots, so clients can tell a malformed file apart.
      call.respond<KetchError>(HttpStatusCode.UnprocessableEntity, e)
    } catch (e: UnsupportedOperationException) {
      call.respond(
        HttpStatusCode.NotImplemented,
        ErrorResponse("unsupported", e.message ?: "Resolving file content is unavailable"),
      )
    }
  }
}

/** Upper bound for uploaded content; `.torrent` metainfo is limited to 4 MiB by default. */
internal const val MAX_RESOLVE_CONTENT_BYTES = 16 * 1024 * 1024
