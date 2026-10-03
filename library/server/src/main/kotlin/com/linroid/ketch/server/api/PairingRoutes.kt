package com.linroid.ketch.server.api

import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.PairingRequest
import com.linroid.ketch.server.PairingSessions
import com.linroid.ketch.server.isWebOrigin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Installs the pairing endpoints, which take no access token: a device asks for the token, the
 * owner of this one answers through [sessions], and the device polls for the answer.
 *
 * Web pages are refused, since the server lets pages on other sites call it once they have the
 * token: a page that got the owner to allow it would receive the token. Anyone on the network
 * can send a request, so its body is capped at [MAX_PAIRING_BODY_BYTES] before it is read, and
 * the name and system lose the characters that could forge log lines or disguise the name.
 */
internal fun Route.pairingRoutes(sessions: PairingSessions) {
  post<Api.Pairing> {
    if (refuseWebPage(call)) return@post
    val bytes = readBounded(call, MAX_PAIRING_BODY_BYTES)
    if (bytes == null) {
      val message = "Pairing requests are $MAX_PAIRING_BODY_BYTES bytes at most"
      call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large", message))
      return@post
    }
    val body = WireJson.decodeFromString<PairingRequest>(bytes.decodeToString())
    val name = shownText(body.name, MAX_NAME_LENGTH)
    require(name.isNotEmpty()) { "name must not be blank" }
    require(CODE.matches(body.code)) { "code must be four digits" }
    val request = PairingRequest(
      name = name,
      code = body.code,
      os = body.os?.let { shownText(it, MAX_OS_LENGTH) }?.ifEmpty { null },
    )
    val ticket = sessions.open(request, call.request.origin.remoteAddress)
    if (ticket == null) {
      call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse("pairing_busy", "Another pairing request is waiting for an answer"),
      )
    } else {
      call.respond(HttpStatusCode.Accepted, ticket)
    }
  }

  get<Api.Pairing.ById> { resource ->
    if (refuseWebPage(call)) return@get
    val status = sessions.status(resource.id)
    if (status == null) {
      call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "No such pairing request"))
    } else {
      call.respond(status)
    }
  }

  delete<Api.Pairing.ById> { resource ->
    if (refuseWebPage(call)) return@delete
    sessions.withdraw(resource.id)
    call.respond(HttpStatusCode.NoContent)
  }
}

/** Answers `403 Forbidden` and returns `true` when [call] comes from a web page. */
private suspend fun refuseWebPage(call: ApplicationCall): Boolean {
  val origin = call.request.headers[HttpHeaders.Origin] ?: return false
  if (!isWebOrigin(origin)) return false
  call.respond(
    HttpStatusCode.Forbidden,
    ErrorResponse("origin_not_allowed", "Web pages cannot pair with this server"),
  )
  return true
}

/**
 * [text] as the owner sees it in a dialog and the logs: without control characters such as
 * newlines, invisible formatting such as bidirectional overrides, or line separators, trimmed
 * and at most [max] characters, without splitting a surrogate pair.
 */
internal fun shownText(text: String, max: Int): String {
  val kept = text.filterNot(::isHidden).trim()
  if (kept.length <= max) return kept
  val end = if (kept[max - 1].isHighSurrogate()) max - 1 else max
  return kept.substring(0, end).trimEnd()
}

private fun isHidden(char: Char): Boolean = char.isISOControl() ||
  when (Character.getType(char).toByte()) {
    Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
    else -> false
  }

private val WireJson = Json { ignoreUnknownKeys = true }
private val CODE = Regex("[0-9]{4}")
private const val MAX_NAME_LENGTH = 64
private const val MAX_OS_LENGTH = 64

/** Upper bound of a pairing request's body, ample for its name, code and system. */
internal const val MAX_PAIRING_BODY_BYTES = 4 * 1024
