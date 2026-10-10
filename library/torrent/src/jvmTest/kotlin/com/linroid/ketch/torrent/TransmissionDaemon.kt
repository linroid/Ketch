package com.linroid.ketch.torrent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An isolated Transmission daemon on loopback, driven over its RPC API: a test fixture, never a
 * production downloader dependency. It runs without DHT, local discovery, port mapping or uTP,
 * tolerates plaintext peers and seeds without a ratio limit.
 */
internal class TransmissionDaemon private constructor(
  private val process: Process,
  private val log: File,
  private val rpcPort: Int,
  /** The port Transmission takes peers on. */
  val peerPort: Int,
) : AutoCloseable {
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
  private var token: String? = null

  /** Calls [method] and returns its arguments, negotiating the RPC session id first. */
  fun rpc(method: String, arguments: JsonObject = buildJsonObject {}): JsonObject {
    val body = buildJsonObject {
      put("method", method)
      put("arguments", arguments)
    }.toString()
    repeat(2) {
      val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$rpcPort/transmission/rpc"))
        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body))
      token?.let { request.header("X-Transmission-Session-Id", it) }
      val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 409) {
        token = response.headers().firstValue("X-Transmission-Session-Id").orElseThrow()
      } else {
        assertEquals(200, response.statusCode())
        val value = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("success", value["result"]?.jsonPrimitive?.content, response.body())
        return value["arguments"]!!.jsonObject
      }
    }
    error("Transmission RPC session negotiation failed")
  }

  /** Waits until the daemon answers RPC calls; [start] already did. */
  suspend fun awaitReady() {
    while (true) {
      check(process.isAlive) { log.readText() }
      try { rpc("session-get"); return } catch (_: IOException) { delay(50) }
    }
  }

  /** Adds the torrent [metainfo], whose files are, or are to be, in [downloadDir]. */
  fun add(metainfo: ByteArray, downloadDir: File) {
    rpc("torrent-add", buildJsonObject {
      put("metainfo", encodeBase64(metainfo)); put("download-dir", downloadDir.absolutePath)
    })
  }

  /** Waits until the only torrent has every piece, verified or downloaded. */
  suspend fun awaitComplete() {
    while (true) {
      check(process.isAlive) { log.readText() }
      val torrents = rpc("torrent-get", Json.parseToJsonElement(
        "{\"fields\":[\"percentDone\",\"error\",\"errorString\"]}").jsonObject)["torrents"]!!
        .jsonArray
      val torrent = torrents.firstOrNull()?.jsonObject
      val error = torrent?.get("error")?.jsonPrimitive?.content?.toInt() ?: 0
      check(error == 0) { "Transmission torrent failed: ${torrent?.get("errorString")}" }
      if (torrent?.get("percentDone")?.jsonPrimitive?.content?.toDouble() == 1.0) return
      delay(50)
    }
  }

  override fun close() {
    process.destroy()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
  }

  companion object {
    /**
     * The daemon named by `TRANSMISSION_DAEMON`, checked against the pinned version and reported
     * as a conformance client.
     */
    fun binary(): String {
      val binary = checkNotNull(System.getenv("TRANSMISSION_DAEMON")?.takeIf { it.isNotBlank() }) {
        "Transmission conformance requires TRANSMISSION_DAEMON; see test-fixtures/torrent/README.md"
      }
      val version = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
      val exited = version.waitFor(5, TimeUnit.SECONDS)
      if (!exited) version.destroyForcibly().waitFor()
      assertTrue(exited, "Transmission version check timed out")
      val versionText = version.inputStream.bufferedReader().readText().trim()
      assertEquals(0, version.exitValue(), versionText)
      val expected = ConformanceClients.version("transmission")
      assertTrue(versionText.startsWith("transmission-daemon $expected "), versionText)
      println("CONFORMANCE_CLIENT $versionText")
      return binary
    }

    /**
     * Starts [binary] with its configuration and log under [root], saving to [downloadDir], and
     * waits until it answers RPC calls. Its ports are free when chosen but may be taken before it
     * binds them; it then starts again on fresh ones.
     */
    suspend fun start(binary: String, root: File, downloadDir: File): TransmissionDaemon {
      var failure: Throwable? = null
      repeat(TRANSMISSION_START_ATTEMPTS) { attempt ->
        val rpcPort = freePort()
        val peerPort = freePort()
        val log = root.resolve("transmission-$attempt.log")
        val process = ProcessBuilder(binary, "--foreground", "--config-dir",
          root.resolve("config").absolutePath, "--download-dir", downloadDir.absolutePath,
          "--port", rpcPort.toString(), "--peerport", peerPort.toString(),
          "--rpc-bind-address", "127.0.0.1", "--bind-address-ipv4", "127.0.0.1",
          "--no-auth", "--no-dht", "--no-lpd", "--no-portmap", "--no-utp",
          "--encryption-tolerated", "--no-global-seedratio")
          .redirectErrorStream(true).redirectOutput(log).start()
        val daemon = TransmissionDaemon(process, log, rpcPort, peerPort)
        try {
          withTimeout(30_000) { daemon.awaitReady() }
          if (!transmissionBindFailed(log)) return daemon
          failure = IllegalStateException(log.readText())
        } catch (error: Throwable) {
          currentCoroutineContext().ensureActive()
          failure = error
        }
        daemon.close()
      }
      throw AssertionError("Transmission did not start on free ports", failure)
    }
  }
}

/** Starts of a Transmission fixture before a port taken meanwhile fails the test. */
internal const val TRANSMISSION_START_ATTEMPTS = 3

/** A port free now; whoever binds it later may find it taken and must try another. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Transmission logs a port it could not bind and carries on without it. */
internal fun transmissionBindFailed(log: File): Boolean =
  log.readLines().any { " ERR " in it && "bind" in it.lowercase() }
