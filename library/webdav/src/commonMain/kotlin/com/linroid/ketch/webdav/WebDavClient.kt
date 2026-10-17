@file:OptIn(ExperimentalEncodingApi::class)

package com.linroid.ketch.webdav

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Requests one WebDAV resource, [url], through [engine], signing in as a server asks.
 *
 * Credentials come from the link's user information, else from an `Authorization: Basic` header
 * among [headers], such as the one the apps' sign-in adds; the header is then left out, and
 * requests answer the server's challenge instead, Digest where it offers it. No credentials are
 * sent before the server asks for them. Any other `Authorization` header is sent as it is.
 *
 * A `401` with no credentials to answer it, or one that refuses them, fails with
 * [KetchError.AuthenticationFailed]. A Digest nonce that expires during a download is answered
 * again ([download]).
 *
 * A redirect to another path of the same origin, such as a folder's trailing slash or a
 * canonical path, moves the client there: later requests go to that URL and Digest answers name
 * its target, as the server checks them against the request it received.
 */
internal class WebDavClient(
  private val engine: HttpEngine,
  private val url: WebDavUrl,
  headers: Map<String, String>,
) {
  private val log = KetchLogger("WebDavSource")
  private val basicHeader: Credentials? = headers.entries
    .firstOrNull { it.key.equals(AUTHORIZATION, ignoreCase = true) }
    ?.value?.let(::basicCredentials)
  private val credentials: Credentials? = url.credentials ?: basicHeader
  private val headers: Map<String, String> =
    if (url.credentials != null || basicHeader != null) {
      headers.filterKeys { !it.equals(AUTHORIZATION, ignoreCase = true) }
    } else {
      headers
    }
  private val authenticator = credentials?.let(::Authenticator)
  private val challengeLock = Mutex()

  /** Counts the challenges answered, so concurrent failures answer a new one only once. */
  @Volatile
  private var generation = 0

  /** Where requests go: the link's URL, or the same-origin one a redirect led to. */
  @Volatile
  private var target = Target(url.httpUrl, url.requestTarget)

  /** The headers of a [method] request to [to], with credentials once a challenge was answered. */
  private fun headers(method: String, to: Target = target): Map<String, String> {
    val authorization = authenticator?.authorization(method, to.requestTarget)
      ?: return headers
    return headers + (AUTHORIZATION to authorization)
  }

  /**
   * The resource's properties, or `null` when the server does not answer `PROPFIND` for it
   * (`403`, `405` or `501`), so it is downloaded as a plain HTTP resource.
   *
   * @throws KetchError.Http for another error status, such as `404`
   */
  suspend fun propfind(): DavProperties? {
    val body = HttpBody(DavProperties.REQUEST.encodeToByteArray(), XML)
    val reply = try {
      send(PROPFIND, mapOf(DEPTH to "0"), body)
    } catch (e: KetchError.Unsupported) {
      // A server that takes PROPFIND for GET sends the file itself, too long for an answer.
      log.d { "PROPFIND answer too long (${e.describeCauses()}); reading the file as plain HTTP" }
      return null
    }
    when {
      reply.status == 207 -> Unit
      reply.status in NOT_DAV || reply.isSuccess -> {
        log.d { "PROPFIND answered ${reply.status}; reading the file as plain HTTP" }
        return null
      }
      else -> throw KetchError.Http(reply.status)
    }
    return try {
      DavProperties.parse(reply.body.decodeToString())
    } catch (e: MissingResourceException) {
      throw KetchError.Http(e.status, cause = e)
    } catch (e: IllegalArgumentException) {
      throw KetchError.SourceError(
        WebDavDownloadSource.TYPE, e, "The server's PROPFIND answer could not be read",
      )
    }
  }

  /**
   * What a ranged `GET` of the first byte says about the resource ([HttpEngine.probe]), else a
   * `HEAD` for engines that cannot probe.
   */
  suspend fun probe(): ServerInfo = authorized {
    val to = target
    try {
      engine.probe(to.httpUrl, headers("GET", to))
    } catch (_: UnsupportedOperationException) {
      engine.head(to.httpUrl, headers(HEAD, to))
    }
  }

  /**
   * [HttpEngine.download] of [range] with [conditions], such as `If-Match`, answering a challenge
   * the server makes first.
   */
  suspend fun download(
    range: LongRange?,
    conditions: Map<String, String> = emptyMap(),
    onData: suspend (ByteArray) -> Unit,
  ) {
    authorized {
      val to = target
      engine.download(to.httpUrl, range, headers("GET", to) + conditions, onData)
    }
  }

  /**
   * Runs [block], and once more after answering the server's challenge when it fails with `401`
   * before any data, as when a Digest nonce expired.
   */
  private suspend fun <T> authorized(block: suspend () -> T): T {
    val seen = generation
    try {
      return block()
    } catch (e: KetchError.Http) {
      if (e.code != 401) throw e
      if (authenticator == null) throw authenticationFailed(e)
      challengeLock.withLock {
        // Another request may have answered a new challenge meanwhile.
        if (generation == seen) {
          val to = target
          val reply = engine.request(HEAD, to.httpUrl, headers(HEAD, to))
          follow(reply)
          if (reply.status == 401) answer(reply)
        }
      }
      try {
        return block()
      } catch (retry: KetchError.Http) {
        if (retry.code == 401) throw authenticationFailed(retry)
        throw retry
      }
    }
  }

  /** Sends [method], answering a `401` challenge once. */
  private suspend fun send(
    method: String,
    extra: Map<String, String>,
    body: HttpBody?,
  ): HttpReply {
    val first = target
    val reply = engine.request(method, first.httpUrl, headers(method, first) + extra, body)
    follow(reply)
    if (reply.status != 401) return reply
    if (authenticator == null) throw authenticationFailed(null)
    challengeLock.withLock { answer(reply) }
    // Sent where the challenge came from, so its Digest names the target the server checks.
    val to = target
    val retried = engine.request(method, to.httpUrl, headers(method, to) + extra, body)
    follow(retried)
    if (retried.status == 401) throw authenticationFailed(null)
    return retried
  }

  /**
   * Moves [target] to the URL that answered [reply] when a redirect led to another path of the
   * same origin. Another origin never gets the credentials, so the client stays.
   */
  private fun follow(reply: HttpReply) {
    val answered = reply.url ?: return
    val current = target
    if (answered == current.httpUrl || origin(answered) != origin(current.httpUrl)) return
    log.d { "Redirected to another path of the same origin; requesting it from now on" }
    target = Target(answered, requestTargetOf(answered))
  }

  /** Answers the challenges of [reply], a `401`; must hold [challengeLock]. */
  private fun answer(reply: HttpReply) {
    val auth = authenticator ?: throw authenticationFailed(null)
    val challenges = Challenge.parse(reply.headers(WWW_AUTHENTICATE))
    if (!auth.accept(challenges)) {
      log.d { "Credentials refused or no supported scheme in ${challenges.map { it.scheme }}" }
      throw authenticationFailed(null)
    }
    generation++
    val scheme = auth.scheme
    log.d { "Answering the server's $scheme challenge" }
    if (scheme == "Basic" && !url.isSecure) {
      log.w { "Sending Basic credentials without TLS; use a davs:// or webdavs:// link" }
    }
  }

  private fun authenticationFailed(cause: Throwable?) =
    KetchError.AuthenticationFailed(WebDavDownloadSource.TYPE, cause)

  /** A URL requests go to, and its path and query as Digest answers name them. */
  private class Target(val httpUrl: String, val requestTarget: String)

  internal companion object {
    /** The scheme, host and port of an HTTP(S) [url], lowercase, with the default port. */
    internal fun origin(url: String): String {
      val scheme = url.substringBefore("://").lowercase()
      val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@').lowercase()
      val defaultPort = if (scheme == "https") ":443" else ":80"
      return "$scheme://" + authority.removeSuffix(defaultPort)
    }

    /** The path and query of an HTTP(S) [url], `/` for none. */
    internal fun requestTargetOf(url: String): String {
      val rest = url.substringAfter("://").substringBefore('#')
      val start = rest.indexOfFirst { it == '/' || it == '?' }
      if (start < 0) return "/"
      val target = rest.substring(start)
      return if (target.startsWith('?')) "/$target" else target
    }

    const val AUTHORIZATION = "Authorization"
    const val WWW_AUTHENTICATE = "WWW-Authenticate"
    const val DEPTH = "Depth"
    const val PROPFIND = "PROPFIND"
    const val HEAD = "HEAD"
    const val XML = "application/xml; charset=utf-8"

    /** `PROPFIND` statuses of servers that serve the file but not its properties. */
    val NOT_DAV = setOf(403, 405, 501)

    /** The user name and password of a `Basic` [header], or `null` for another scheme. */
    fun basicCredentials(header: String): Credentials? {
      val value = header.trim()
      if (!value.startsWith("Basic ", ignoreCase = true)) return null
      val decoded = try {
        Base64.decode(value.substring(6).trim()).decodeToString()
      } catch (_: IllegalArgumentException) {
        return null
      }
      val colon = decoded.indexOf(':')
      if (colon < 0) return null
      return Credentials(decoded.substring(0, colon), decoded.substring(colon + 1))
    }
  }
}
