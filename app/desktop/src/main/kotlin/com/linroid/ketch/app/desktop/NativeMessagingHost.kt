package com.linroid.ketch.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The native messaging host the browser extension talks to, so it can reach this app without
 * the API server users turn on in Settings. The browser starts this app's launcher with [FLAG]
 * (see [NativeHostRegistration]); the host reads one request, gets the address and token of
 * [BrowserExtensionServer] from the running app, starting the app first if asked to, replies and
 * exits.
 *
 * Requests are `{"type":"connect","launch":true}`. Replies are the app's
 * `{"url":…,"token":…}`, or `{"error":…,"message":…}`.
 */
internal class NativeMessagingHost(
  private val requestApp: () -> String?,
  private val launchApp: () -> Unit,
  private val startTimeout: Duration = 30.seconds,
  private val pollInterval: Duration = 250.milliseconds,
) {

  /** Answers one request read from [input] on [output]; does nothing if [input] is empty. */
  fun serve(input: InputStream, output: OutputStream) {
    val request = readNativeMessage(input) ?: return
    writeNativeMessage(output, handle(request))
  }

  internal fun handle(request: String): String {
    val message = runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull()
      ?: return errorReply("bad_request", "The request is not a JSON object")
    return when (message["type"]?.jsonPrimitive?.contentOrNull) {
      "connect" -> connect(launch = message["launch"]?.jsonPrimitive?.booleanOrNull ?: true)
      else -> errorReply("bad_request", "Unknown request type")
    }
  }

  private fun connect(launch: Boolean): String {
    requestApp()?.let { return it }
    if (!launch) return errorReply("not_running", "Ketch isn't running")
    try {
      launchApp()
    } catch (e: IOException) {
      return errorReply("launch_failed", "Couldn't start Ketch: ${e.message}")
    }
    val deadline = TimeSource.Monotonic.markNow() + startTimeout
    while (deadline.hasNotPassedNow()) {
      Thread.sleep(pollInterval.inWholeMilliseconds)
      requestApp()?.let { return it }
    }
    return errorReply("not_started", "Ketch didn't start in time")
  }

  companion object {
    /** Launch argument that runs the app as the native messaging host. */
    const val FLAG = "--native-messaging-host"

    /** [SingleInstance] request for [BrowserExtensionServer]'s address and token. */
    const val CONNECT_REQUEST = "browser-extension/connect"

    /** Name of the host, as the extension and the host manifests know it. */
    const val NAME = "com.linroid.ketch"
  }
}

/** Longest request accepted from the browser; real requests are a few dozen bytes. */
private const val MAX_REQUEST_BYTES = 64 * 1024

/**
 * Reads one message: its length as a 32-bit integer in native byte order, then that many bytes
 * of UTF-8 JSON. Returns null when the browser closed the stream without sending one.
 */
internal fun readNativeMessage(input: InputStream): String? {
  val header = ByteArray(Int.SIZE_BYTES)
  val headerRead = input.readUpTo(header)
  if (headerRead == 0) return null
  if (headerRead < header.size) throw IOException("Truncated message length")
  val length = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).int
  if (length !in 0..MAX_REQUEST_BYTES) throw IOException("A message of $length bytes is too long")
  val body = ByteArray(length)
  if (input.readUpTo(body) < length) throw IOException("Truncated message")
  return body.decodeToString()
}

/**
 * Fills [buffer] unless the stream ends first, returning how many bytes were read. Unlike
 * `FileInputStream.readNBytes`, this never seeks, which fails on the pipes browsers connect.
 */
private fun InputStream.readUpTo(buffer: ByteArray): Int {
  var total = 0
  while (total < buffer.size) {
    val read = read(buffer, total, buffer.size - total)
    if (read < 0) break
    total += read
  }
  return total
}

/** Writes one message in the framing [readNativeMessage] reads. */
internal fun writeNativeMessage(output: OutputStream, message: String) {
  val body = message.encodeToByteArray()
  val header = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).putInt(body.size)
  output.write(header.array())
  output.write(body)
  output.flush()
}

/** Runs this process as the native messaging host, then returns so it can exit. */
internal fun runNativeMessagingHost(configDir: File) {
  // Only messages may reach stdout, and the host must never show up as an app.
  val stdout = FileOutputStream(FileDescriptor.out)
  System.setOut(System.err)
  System.setProperty("java.awt.headless", "true")
  val host = NativeMessagingHost(
    requestApp = { SingleInstance.request(configDir, NativeMessagingHost.CONNECT_REQUEST) },
    launchApp = { AppCommand.current().launchDetached() },
  )
  try {
    host.serve(FileInputStream(FileDescriptor.`in`), stdout)
  } catch (e: IOException) {
    System.err.println("Ketch native messaging host: ${e.message}")
  }
}

internal fun errorReply(code: String, message: String): String = buildJsonObject {
  put("error", code)
  put("message", message)
}.toString()
