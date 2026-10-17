@file:OptIn(ExperimentalEncodingApi::class)

package com.linroid.ketch.webdav

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.digest.Md5
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A WebDAV server holding one file, as an [HttpEngine]: it answers `PROPFIND`, probes and ranged
 * downloads of [path], and checks Basic or Digest credentials when [auth] says so.
 */
internal class FakeDavServer(
  var content: ByteArray,
  var etag: String? = "\"v1\"",
  var lastModified: String? = "Mon, 12 Oct 2026 08:00:00 GMT",
  val path: String = "/dav/file.bin",
  var auth: Auth = Auth.None,
) : HttpEngine {
  enum class Auth { None, Basic, Digest }

  /** Whether `PROPFIND` works; otherwise it answers 405. */
  var dav = true
  var collection = false
  var ranges = true
  var reportsLength = true
  var user = "user"
  var password = "secret"
  var nonce = "nonce-1"

  /** Each request: method, then `+` when its credentials passed or none were needed. */
  val requests = mutableListOf<String>()
  val ranged = mutableListOf<LongRange?>()

  /** The conditional headers of each download, such as `If-Match: "v1"`. */
  val conditions = mutableListOf<List<String>>()

  /** A `Range` request the server ends early, after this many bytes, once. */
  var dropAfter: Long? = null

  override suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: HttpBody?,
    maxBodyBytes: Int,
  ): HttpReply {
    check(url.endsWith(path)) { "Unexpected URL" }
    if (!authorized(method, headers)) return challenge()
    return when (method) {
      "PROPFIND" -> {
        if (!dav) return HttpReply(405, emptyMap(), ByteArray(0))
        check(headers["Depth"] == "0")
        check(body != null && body.contentType.startsWith("application/xml"))
        HttpReply(207, emptyMap(), multistatus().encodeToByteArray())
      }
      "HEAD" -> HttpReply(200, emptyMap(), ByteArray(0))
      else -> HttpReply(405, emptyMap(), ByteArray(0))
    }
  }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    if (!authorized("HEAD", headers)) throw KetchError.Http(401)
    return info()
  }

  override suspend fun probe(url: String, headers: Map<String, String>): ServerInfo {
    if (!authorized("GET", headers)) throw KetchError.Http(401)
    return info()
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    if (!authorized("GET", headers)) throw KetchError.Http(401)
    ranged += range
    val ifMatch = headers["If-Match"]
    val ifUnmodifiedSince = headers["If-Unmodified-Since"]
    conditions += listOfNotNull(
      ifMatch?.let { "If-Match: $it" },
      ifUnmodifiedSince?.let { "If-Unmodified-Since: $it" },
    )
    if (ifMatch != null && ifMatch != etag) throw KetchError.Http(412)
    if (ifUnmodifiedSince != null && ifUnmodifiedSince != lastModified) throw KetchError.Http(412)
    if (range != null && !ranges && range.first != 0L) {
      throw KetchError.Unsupported(IllegalStateException("Server returned a different range"))
    }
    val bytes = if (range != null && ranges) {
      content.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
    } else {
      content
    }
    val drop = dropAfter
    if (drop != null && range != null) {
      dropAfter = null
      bytes.copyOf(drop.toInt()).toList().chunked(4).forEach { onData(it.toByteArray()) }
      throw KetchError.Network(IllegalStateException("Connection reset"))
    }
    bytes.toList().chunked(64).forEach { onData(it.toByteArray()) }
  }

  override fun close() {}

  private fun info() = ServerInfo(
    contentLength = if (reportsLength) content.size.toLong() else null,
    acceptRanges = ranges,
    etag = etag,
    lastModified = lastModified,
    contentType = "application/octet-stream",
  )

  private fun multistatus(): String = buildString {
    append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>")
    append("<d:href>$path</d:href><d:propstat><d:prop>")
    append(if (collection) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>")
    if (reportsLength) append("<d:getcontentlength>${content.size}</d:getcontentlength>")
    etag?.let { append("<d:getetag>${it.replace("\"", "&quot;")}</d:getetag>") }
    lastModified?.let { append("<d:getlastmodified>$it</d:getlastmodified>") }
    append("<d:displayname>Display Name.bin</d:displayname>")
    append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
    append("</d:multistatus>")
  }

  private fun challenge(): HttpReply {
    val value = when (auth) {
      Auth.Basic -> "Basic realm=\"files\""
      else -> "Digest realm=\"files\", qop=\"auth\", nonce=\"$nonce\", opaque=\"o\""
    }
    return HttpReply(401, mapOf("www-authenticate" to listOf(value)), ByteArray(0))
  }

  private fun authorized(method: String, headers: Map<String, String>): Boolean {
    val ok = when (auth) {
      Auth.None -> true
      Auth.Basic ->
        headers["Authorization"] == "Basic " + Base64.encode("$user:$password".encodeToByteArray())
      Auth.Digest -> digestMatches(method, headers["Authorization"])
    }
    requests += method + if (ok) "+" else ""
    return ok
  }

  private fun digestMatches(method: String, header: String?): Boolean {
    if (header == null || !header.startsWith("Digest ")) return false
    val params = Challenge.parse(listOf(header)).single().params
    if (params["nonce"] != nonce || params["username"] != user) return false
    if (params["uri"] != path || params["opaque"] != "o") return false
    val ha1 = md5("$user:files:$password")
    val ha2 = md5("$method:${params["uri"]}")
    val expected = md5("$ha1:$nonce:${params["nc"]}:${params["cnonce"]}:auth:$ha2")
    return params["response"] == expected
  }

  private fun md5(text: String): String = Md5().update(text.encodeToByteArray()).digest()
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** A file in memory. */
internal class MemoryFile : FileAccessor {
  var data = ByteArray(0)

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    val end = offset.toInt() + data.size
    if (end > this.data.size) this.data = this.data.copyOf(end)
    data.copyInto(this.data, offset.toInt())
  }

  override suspend fun flush() {}

  override fun close() {}

  override suspend fun delete() {
    data = ByteArray(0)
  }

  override suspend fun size(): Long = data.size.toLong()

  override suspend fun preallocate(size: Long) {
    data = data.copyOf(size.toInt())
  }
}
