package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class MediaDownloadIntegrationTest {
  @Test
  fun download_redirectedManifests_produceCompleteMediaFiles() = runTest(timeout = 30.seconds) {
    val directory = Files.createTempDirectory("ketch-media-").toFile()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val engine = KtorHttpEngine()
    val ketch = Ketch(httpEngine = engine, config = DownloadConfig(retryCount = 0))
    fun resource(path: String): ByteArray = checkNotNull(
      javaClass.getResourceAsStream("/media/$path")
    ).use { it.readBytes() }
    server.createContext("/") { exchange ->
      exchange.use {
        val path = it.requestURI.path.removePrefix("/")
        if (path.startsWith("redirect/")) {
          it.responseHeaders.add("Location", "/" + path.removePrefix("redirect/"))
          it.sendResponseHeaders(302, -1)
        } else {
          val bytes = resource(path)
          it.sendResponseHeaders(200, bytes.size.toLong())
          it.responseBody.write(bytes)
        }
      }
    }
    server.start()
    try {
      ketch.start()
      for ((manifest, parts) in mapOf(
        "hls/index.m3u8" to listOf("hls/index0.ts", "hls/index1.ts"),
        "dash/index.mpd" to listOf("dash/init-stream0.m4s",
          "dash/chunk-stream0-00001.m4s", "dash/chunk-stream0-00002.m4s")
      )) {
        val task = ketch.download(DownloadRequest(
          url = "http://127.0.0.1:${server.address.port}/redirect/$manifest",
          destination = Destination(directory.absolutePath + "/"),
        ))
        val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
        val expected = parts.fold(byteArrayOf()) { bytes, part -> bytes + resource(part) }
        val file = File(state.outputPath)
        assertContentEquals(expected, file.readBytes())
        assertEquals(expected.size.toLong(), state.totalBytes)
        assertEquals(if (manifest.endsWith("m3u8")) "ts" else "mp4", file.extension)
      }
    } finally {
      ketch.close()
      engine.close()
      server.stop(0)
      directory.deleteRecursively()
    }
  }
}
