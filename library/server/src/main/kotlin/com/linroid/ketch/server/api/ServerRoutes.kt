package com.linroid.ketch.server.api

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private val log = KetchLogger("ServerRoutes")

/**
 * Installs server-level status, configuration, network interface, and URL resolution endpoints.
 */
internal fun Route.serverRoutes(ketch: KetchApi) {
  get<Api.Status> {
    log.d { "GET /api/status" }
    call.respond(ketch.status())
  }

  put<Api.Config> {
    val body = call.receive<DownloadConfig>()
    log.i { "PUT /api/config: speedLimit=${body.speedLimit}" }
    ketch.updateConfig(body)
    call.respond(body)
  }

  get<Api.NetworkInterfaces> {
    call.respond(ketch.networkInterfaces())
  }

  put<Api.NetworkInterfaces> {
    val body = call.receive<NetworkInterfaceConfig>()
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
    val body = call.receive<ResolveUrlRequest>()
    log.i { "POST /api/resolve url=${body.url}" }
    val resolved = ketch.resolve(body.url, body.properties)
    call.respond(resolved)
  }
}
