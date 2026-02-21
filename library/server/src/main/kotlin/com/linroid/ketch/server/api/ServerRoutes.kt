package com.linroid.ketch.server.api

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Installs server-level endpoints: status, global speed limit,
 * and URL resolution.
 */
internal fun Route.serverRoutes(ketch: KetchApi) {
  get<Api.Status> {
    call.respond(ketch.status())
  }

  put<Api.Config> {
    val body = call.receive<DownloadConfig>()
    ketch.updateConfig(body)
    call.respond(body)
  }

  post<Api.Resolve> {
    val body = call.receive<ResolveUrlRequest>()
    val resolved = ketch.resolve(body.url, body.headers)
    call.respond(resolved)
  }
}
