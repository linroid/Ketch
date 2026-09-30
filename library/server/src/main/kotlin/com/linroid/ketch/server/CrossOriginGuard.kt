package com.linroid.ketch.server

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respond

private val log = KetchLogger("KetchServer")

/**
 * Responds `403 Forbidden` to requests from web pages on other origins, before CORS,
 * authentication or routing see them.
 *
 * [KetchServer] installs it when it has no API token. Leaving out CORS headers alone is not
 * enough: a page can still send requests that need no preflight, such as a form-encoded POST,
 * and act on the API without reading the responses.
 */
internal val CrossOriginGuard: ApplicationPlugin<Unit> =
  createApplicationPlugin("CrossOriginGuard") {
    onCall { call ->
      val host = call.request.headers[HttpHeaders.Host]
      val origin = call.request.headers.getAll(HttpHeaders.Origin)
        ?.firstOrNull { isForeignWebOrigin(it, host) }
        ?: return@onCall
      log.w { "Refused request from $origin: pages on other origins need an API token" }
      call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse(
          "origin_not_allowed",
          "Web pages from other origins can't use this server without an API token.",
        ),
      )
    }
  }

/**
 * Whether [origin], an `Origin` header, belongs to a web page: an `http` or `https` origin, or
 * `null`, which sandboxed frames and `file:` or `data:` pages send.
 *
 * Browser extensions (`chrome-extension:`, `moz-extension:`) and apps' web views have other
 * schemes. A browser only lets an extension reach Ketch with the host permission the user
 * granted it, and an app on this device can call the API directly anyway.
 */
internal fun isWebOrigin(origin: String): Boolean {
  return origin == "null" || origin.substringBefore("://", "").lowercase() in WEB_SCHEMES
}

/**
 * Whether [origin], an `Origin` header, is a web page on another origin than the server that
 * [host], the request's `Host` header, names.
 *
 * Pages served by this server, such as the bundled web UI, send an origin whose host and port
 * match [host]. The scheme is not compared, so the web UI also works behind a proxy that
 * terminates TLS and keeps the `Host` header.
 */
internal fun isForeignWebOrigin(origin: String, host: String?): Boolean {
  if (!isWebOrigin(origin)) return false
  if (origin == "null" || host == null) return true
  val scheme = origin.substringBefore("://").lowercase()
  val authority = origin.substringAfter("://").removeSuffix("/")
  if (authority.isEmpty() || authority.any { it in "/?#@" }) return true
  val defaultPort = if (scheme == "https") 443 else 80
  return normalizeAuthority(authority, defaultPort) != normalizeAuthority(host, defaultPort)
}

/** [authority] as lowercase `host:port`, with [defaultPort] when it has no port. */
private fun normalizeAuthority(authority: String, defaultPort: Int): String {
  val text = authority.trim().lowercase()
  val colon = text.lastIndexOf(':')
  // A colon inside an IPv6 literal such as `[::1]` does not start a port.
  if (colon < 0 || colon < text.lastIndexOf(']')) return "$text:$defaultPort"
  val port = text.substring(colon + 1).ifEmpty { defaultPort.toString() }
  return "${text.substring(0, colon)}:$port"
}

private val WEB_SCHEMES = setOf("http", "https")
