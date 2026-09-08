package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An isolated Transmission process is a test fixture, never a production downloader dependency. */
class TransmissionInteropTest {
  @Test
  fun publicSource_resolvesTrackerlessMagnetAndDownloadsFromTransmission() = runTest {
    val binary = System.getenv("TRANSMISSION_DAEMON")?.takeIf { it.isNotBlank() } ?: return@runTest
    withContext(Dispatchers.IO) {
      withTimeout(60_000) {
        val version = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
        val versionText = version.inputStream.bufferedReader().readText()
        assertTrue(version.waitFor(5, TimeUnit.SECONDS))
        assertTrue("4.1.3" in versionText || "4.0.5" in versionText, versionText)
        val root = Files.createTempDirectory("ketch-transmission").toFile()
        val seed = root.resolve("seed").apply { mkdirs() }
        val payload = ByteArray(512 * 1024 + 37) { (it * 31 + 17).toByte() }
        seed.resolve("fixture.bin").writeBytes(payload)
        val data = Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture.bin", "length" to payload.size.toLong(), "piece length" to 16_384L,
          "pieces" to payload.asList().chunked(16_384).fold(ByteArray(0)) { hashes, chunk ->
            hashes + sha1Digest(chunk.toByteArray())
          }
        )))
        val metadata = TorrentMetadata.fromBencode(data)
        fun port(): Int = ServerSocket(0).use { it.localPort }
        val rpcPort = port()
        val peerPort = port()
        val process = ProcessBuilder(binary, "--foreground", "--config-dir",
          root.resolve("config").absolutePath, "--download-dir", seed.absolutePath,
          "--port", rpcPort.toString(), "--peerport", peerPort.toString(),
          "--rpc-bind-address", "127.0.0.1", "--bind-address-ipv4", "127.0.0.1",
          "--no-auth", "--no-dht", "--no-lpd", "--no-portmap", "--no-utp",
          "--encryption-tolerated", "--no-global-seedratio")
          .redirectErrorStream(true).redirectOutput(root.resolve("transmission.log")).start()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        var token: String? = null
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
              assertEquals("success", value["result"]?.jsonPrimitive?.content)
              return value["arguments"]!!.jsonObject
            }
          }
          error("Transmission RPC session negotiation failed")
        }
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
        try {
          while (true) {
            check(process.isAlive) { root.resolve("transmission.log").readText() }
            try { rpc("session-get"); break } catch (_: java.io.IOException) { delay(50) }
          }
          rpc("torrent-add", buildJsonObject {
            put("metainfo", encodeBase64(data)); put("download-dir", seed.absolutePath)
          })
          while (true) {
            val torrents = rpc("torrent-get", Json.parseToJsonElement(
              "{\"fields\":[\"percentDone\"]}").jsonObject)["torrents"]!!.jsonArray
            if (torrents.firstOrNull()?.jsonObject?.get("percentDone")?.jsonPrimitive
                ?.content?.toDouble() == 1.0) break
            delay(50)
          }
          val magnet = MagnetUri(metadata.infoHash,
            explicitPeers = listOf("127.0.0.1:$peerPort")).toUri()
          val resolved = source.resolve(magnet, emptyMap())
          val output = root.resolve("result.bin")
          val context = sourceContext(magnet, resolved, output.absolutePath)
          source.download(context)
          assertContentEquals(payload, output.readBytes())
          assertEquals(payload.size.toLong(), context.segments.value.sumOf { it.downloadedBytes })
          for (variable in listOf("KETCH_NATIVE_CLI", "KETCH_JVM_CLI")) {
            val cli = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: continue
            val cliOutput = root.resolve(variable)
            val log = root.resolve("$variable.log")
            val download = ProcessBuilder(cli, magnet, cliOutput.absolutePath)
              .redirectErrorStream(true).redirectOutput(log).start()
            val exited = download.waitFor(30, TimeUnit.SECONDS)
            if (!exited) download.destroyForcibly().waitFor()
            assertTrue(exited, log.readText())
            assertEquals(0, download.exitValue(), log.readText())
            assertTrue(cliOutput.exists(), log.readText())
            assertContentEquals(payload, cliOutput.readBytes())
          }
        } finally {
          source.close()
          process.destroy()
          if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
          root.deleteRecursively()
        }
      }
    }
  }
}
