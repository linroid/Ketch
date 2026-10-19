package com.linroid.ketch.updater

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpBody
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.HttpReply
import com.linroid.ketch.core.engine.ServerInfo
import java.util.Collections

/**
 * Serves [bodies] by URL, with range support; any other URL answers [missingCode]. Requests to a
 * URL of [redirects] answer 200 from the URL it maps to, as after following its redirects.
 */
internal class FakeHttpEngine(
  private val bodies: Map<String, ByteArray>,
  private val missingCode: Int = 404,
  private val redirects: Map<String, String> = emptyMap(),
) : HttpEngine {
  /** URLs and headers of every GET, in order; segments request them from several threads. */
  val requests: MutableList<Pair<String, Map<String, String>>> =
    Collections.synchronizedList(mutableListOf())

  @Volatile
  var closed = false
    private set

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    val body = bodies[url] ?: throw KetchError.Http(missingCode)
    return ServerInfo(
      contentLength = body.size.toLong(),
      acceptRanges = true,
      etag = "\"fake\"",
      lastModified = null,
    )
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    requests += url to headers
    val body = bodies[url] ?: throw KetchError.Http(missingCode)
    val part = range?.let { body.copyOfRange(it.first.toInt(), it.last.toInt() + 1) } ?: body
    part.asList().chunked(CHUNK).forEach { onData(it.toByteArray()) }
  }

  override suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: HttpBody?,
    maxBodyBytes: Int,
  ): HttpReply {
    requests += url to headers
    val target = redirects[url] ?: throw KetchError.Http(missingCode)
    return HttpReply(status = 200, headers = emptyMap(), body = ByteArray(0), url = target)
  }

  override fun close() {
    closed = true
  }

  private companion object {
    const val CHUNK = 1024
  }
}
