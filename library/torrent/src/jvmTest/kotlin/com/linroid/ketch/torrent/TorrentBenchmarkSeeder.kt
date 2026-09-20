package com.linroid.ketch.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Out-of-process reference seeder: no native torrent library is loaded into the measuring JVM. */
internal class TorrentBenchmarkSeeder(
  private val root: File,
  val dataHost: String = benchmarkAddress(),
) {
  private val rpcPort = ServerSocket(0).use { it.localPort }
  val peerPort: Int = ServerSocket(0).use { it.localPort }
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
  private var token: String? = null
  private var process: Process? = null
  private val log = root.resolve("transmission.log")

  val pid: Long get() = checkNotNull(process).pid()
  val cpuNanos: Long get() = checkNotNull(process).info().totalCpuDuration()
    .map { it.toNanos() }.orElse(-1L)

  suspend fun start(metainfo: ByteArray?, seed: File) = withTimeout(120_000) {
    val binary = checkNotNull(System.getenv("TRANSMISSION_DAEMON")?.takeIf { it.isNotBlank() })
    val version = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
    if (!version.waitFor(5, TimeUnit.SECONDS)) {
      version.destroyForcibly()
      error("Transmission version check timed out")
    }
    val text = version.inputStream.bufferedReader().readText()
    check(version.exitValue() == 0 &&
      text.startsWith("transmission-daemon ${ConformanceClients.version("transmission")} "))
    process = ProcessBuilder(binary, "--foreground", "--config-dir",
      root.resolve("config").absolutePath, "--download-dir", seed.absolutePath,
      "--port", rpcPort.toString(), "--peerport", peerPort.toString(),
      "--rpc-bind-address", "127.0.0.1", "--bind-address-ipv4", dataHost,
      "--bind-address-ipv6", "::1",
      "--no-auth", "--no-dht", "--no-lpd", "--no-portmap", "--no-utp",
      "--encryption-tolerated", "--no-global-seedratio")
      .redirectErrorStream(true).redirectOutput(log).start()
    while (true) {
      check(checkNotNull(process).isAlive) { log.readText() }
      try { rpc("session-get"); break } catch (_: java.io.IOException) { delay(50) }
    }
    if (metainfo != null) {
      addTorrent(metainfo, seed)
      val size = TorrentMetadata.fromBencode(metainfo).totalBytes
      while (verifiedBytes() != size) delay(50)
    }
  }

  fun addTorrent(metainfo: ByteArray, output: File) {
    rpc("torrent-add", buildJsonObject {
      put("metainfo", encodeBase64(metainfo)); put("download-dir", output.absolutePath)
    })
  }

  fun verifiedBytes(): Long {
    check(checkNotNull(process).isAlive) { log.readText() }
    val torrents = rpc("torrent-get", Json.parseToJsonElement(
      "{\"fields\":[\"haveValid\"]}").jsonObject)["torrents"]!!.jsonArray
    return torrents.firstOrNull()?.jsonObject?.get("haveValid")?.jsonPrimitive
      ?.content?.toLong() ?: 0
  }

  fun close() {
    process?.let {
      it.destroy()
      if (!it.waitFor(5, TimeUnit.SECONDS)) {
        it.destroyForcibly()
        check(it.waitFor(5, TimeUnit.SECONDS)) { "Transmission did not terminate" }
      }
    }
  }

  private fun rpc(method: String, arguments: JsonObject = buildJsonObject {}): JsonObject {
    val body = buildJsonObject { put("method", method); put("arguments", arguments) }.toString()
    repeat(2) {
      val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$rpcPort/transmission/rpc"))
        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body))
      token?.let { request.header("X-Transmission-Session-Id", it) }
      val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() == 409) {
        token = response.headers().firstValue("X-Transmission-Session-Id").orElseThrow()
      } else {
        check(response.statusCode() == 200)
        val value = Json.parseToJsonElement(response.body()).jsonObject
        check(value["result"]?.jsonPrimitive?.content == "success")
        return value["arguments"]!!.jsonObject
      }
    }
    error("Transmission RPC session negotiation failed")
  }
}

/** Transmission rejects loopback tracker peers; select an address actually assigned to this host. */
internal fun benchmarkAddress(): String = NetworkInterface.networkInterfaces().use { interfaces ->
  interfaces.filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.asIterator().asSequence()
    .filter { address -> address is Inet4Address && address.isSiteLocalAddress }.toList().stream()
  }.findFirst().orElseThrow { IllegalStateException("Benchmark needs a private IPv4 host interface") }
    .hostAddress
}
