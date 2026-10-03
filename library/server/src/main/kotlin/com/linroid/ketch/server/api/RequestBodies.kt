package com.linroid.ketch.server.api

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/** JSON of the API; requests may carry fields this version does not know. */
internal val ServerJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}

/** Upper bound of a JSON request body, ample for every request but a new task's. */
internal const val MAX_JSON_BODY_BYTES = 1024 * 1024

/**
 * Upper bound of a new task's body, which can carry a resolved torrent: its metainfo in Base64
 * and up to 10,000 files.
 */
internal const val MAX_TASK_BODY_BYTES = 32 * 1024 * 1024

/** Thrown when a request body is larger than its route takes; answered with `413`. */
internal class PayloadTooLargeException(val limit: Int) :
  Exception("Request body exceeds $limit bytes")

/**
 * Reads the JSON body of this call as [T]. At most [limit] bytes are read, so a client cannot
 * make the server hold an unbounded body in memory.
 *
 * @throws UnsupportedMediaTypeException if the body is not `application/json`
 * @throws PayloadTooLargeException if the body is longer than [limit]
 */
internal suspend inline fun <reified T> ApplicationCall.receiveJson(
  limit: Int = MAX_JSON_BODY_BYTES,
): T {
  val type = request.contentType()
  if (!type.match(ContentType.Application.Json)) throw UnsupportedMediaTypeException(type)
  val bytes = readBounded(this, limit) ?: throw PayloadTooLargeException(limit)
  return ServerJson.decodeFromString<T>(bytes.decodeToString())
}

/**
 * The body of [call] when it has at most [limit] bytes, or `null` when it has more. Chunked
 * bodies, which declare no length, are read only up to the limit.
 */
internal suspend fun readBounded(call: ApplicationCall, limit: Int): ByteArray? {
  val declared = call.request.contentLength()
  if (declared != null && declared > limit) return null
  val channel = call.receiveChannel()
  val body = ByteArrayOutputStream(declared?.toInt() ?: INITIAL_BODY_CAPACITY)
  // readRemaining(max) can return more than max, so the cap is kept by hand.
  val chunk = ByteArray(READ_CHUNK_BYTES)
  while (true) {
    val count = channel.readAvailable(chunk, 0, chunk.size)
    if (count < 0) break
    if (body.size() + count > limit) return null
    body.write(chunk, 0, count)
  }
  return body.toByteArray()
}

private const val INITIAL_BODY_CAPACITY = 1024
private const val READ_CHUNK_BYTES = 8 * 1024
