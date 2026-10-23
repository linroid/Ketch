package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.resolve
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.dash.DashDownloadSource
import com.linroid.ketch.hls.HlsDownloadSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against a real server, so off the test scheduler: its virtual time would let the stall
 * watchdog that `resolve` fetches manifests under fire while a request is on the wire.
 */
class MediaDownloadIntegrationTest {
  @Test
  fun download_separateSources_produceCompleteMediaFiles() = runTest(timeout = 30.seconds) {
    withContext(Dispatchers.IO) { downloadManifests(legacy = false) }
  }

  @Test
  fun download_legacyResolvedMediaType_routesToInstalledProtocol() = runTest(timeout = 30.seconds) {
    withContext(Dispatchers.IO) { downloadManifests(legacy = true) }
  }

  @Test
  fun download_separateTracks_mergeIntoOneMovie() = runTest(timeout = 30.seconds) {
    withContext(Dispatchers.IO) { downloadTracks() }
  }

  private suspend fun downloadTracks() {
    val directory = Files.createTempDirectory("ketch-media-").toFile()
    val server = serve()
    try {
      // ffmpeg's DASH output with two video representations and an audio adaptation set, and
      // its HLS fMP4 output with a separate audio rendition, its segments AES-128 encrypted.
      for ((manifest, segments) in mapOf(
        // Fragments by start time: video at 0 and 1 s, audio at 0, 0.512 and 1.536 s.
        "dash-tracks/index.mpd" to listOf("chunk-stream0-00001.m4s", "chunk-stream2-00001.m4s",
          "chunk-stream2-00002.m4s", "chunk-stream0-00002.m4s", "chunk-stream2-00003.m4s"),
        // Video at 0 and 1 s, audio at 0, 1.024 and 2.048 s.
        "hls-tracks/index.m3u8" to listOf("seg_video_0.m4s", "seg_audio_0.m4s",
          "seg_video_1.m4s", "seg_audio_1.m4s", "seg_audio_2.m4s"),
      )) {
        val engine = KtorHttpEngine()
        val ketch = Ketch(
          httpEngine = engine,
          config = DownloadConfig(retryCount = 0),
          additionalSources = listOf(HlsDownloadSource(engine), DashDownloadSource(engine)),
        )
        try {
          ketch.start()
          val url = "http://127.0.0.1:${server.address.port}/$manifest"
          val resolved = ketch.resolve(url)
          assertEquals(FileSelectionMode.SINGLE, resolved.selectionMode)
          assertEquals(2, resolved.files.size)
          val task = ketch.download(DownloadRequest(
            url = url,
            resolvedSource = resolved,
            destination = Destination(directory.absolutePath + "/"),
          ))
          val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
          val file = File(state.outputPath)
          assertEquals("mp4", file.extension)
          val out = file.readBytes()
          val top = boxes(out, 0, out.size)
          assertEquals(listOf("ftyp", "moov") + List(segments.size) { listOf("moof", "mdat") }
            .flatten(), top.map { it.first })
          val moov = top[1].second
          assertEquals(2, boxes(out, moov.first + 8, moov.last + 1).count { it.first == "trak" })
          // Every sample of both tracks arrives, decrypted, in the merged file.
          val folder = manifest.substringBefore('/')
          val expected = segments.map { name ->
            val bytes = resource("$folder/$name")
            val clear = if (folder != "hls-tracks") bytes else {
              decrypt(bytes, sequence = name.last { it.isDigit() }.digitToInt())
            }
            mdat(clear).toList()
          }
          val actual = top.filter { it.first == "mdat" }.map { (_, range) ->
            mdat(out.copyOfRange(range.first, range.last + 1)).toList()
          }
          assertEquals(expected, actual)
        } finally {
          ketch.close()
          engine.close()
        }
      }
    } finally {
      server.stop(0)
      directory.deleteRecursively()
    }
  }

  private fun resource(path: String): ByteArray = checkNotNull(
    javaClass.getResourceAsStream("/media/$path")
  ).use { it.readBytes() }

  /** Serves the fixtures over loopback; `redirect/` paths redirect to the rest. */
  private fun serve(): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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
    return server
  }

  /** The top-level boxes of [bytes] between [from] and [to], as types and their byte ranges. */
  private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Pair<String, IntRange>> {
    val buffer = ByteBuffer.wrap(bytes)
    val result = mutableListOf<Pair<String, IntRange>>()
    var at = from
    while (at < to) {
      val size = buffer.getInt(at)
      result += String(bytes, at + 4, 4, Charsets.US_ASCII) to (at until at + size)
      at += size
    }
    return result
  }

  /** The payload of the media data box of a segment. */
  private fun mdat(segment: ByteArray): ByteArray {
    val box = boxes(segment, 0, segment.size).single { it.first == "mdat" }.second
    return segment.copyOfRange(box.first + 8, box.last + 1)
  }

  private fun decrypt(bytes: ByteArray, sequence: Int): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val iv = ByteArray(16).also { it[15] = sequence.toByte() }
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(resource("hls-tracks/key.bin"), "AES"),
      IvParameterSpec(iv))
    return cipher.doFinal(bytes)
  }

  private suspend fun downloadManifests(legacy: Boolean) {
    val directory = Files.createTempDirectory("ketch-media-").toFile()
    val server = serve()
    try {
      for ((manifest, parts) in mapOf(
        "hls/index.m3u8" to listOf("hls/index0.ts", "hls/index1.ts"),
        "dash/index.mpd" to listOf("dash/init-stream0.m4s",
          "dash/chunk-stream0-00001.m4s", "dash/chunk-stream0-00002.m4s")
      )) {
        val engine = KtorHttpEngine()
        val hls = manifest.endsWith("m3u8")
        val source = if (hls) HlsDownloadSource(engine) else DashDownloadSource(engine)
        val ketch = Ketch(
          httpEngine = engine,
          config = DownloadConfig(retryCount = 0),
          additionalSources = listOf(source),
        )
        try {
          ketch.start()
          val features = ketch.status().features
          assertEquals(hls, KetchFeatures.FINITE_HLS in features)
          assertEquals(!hls, KetchFeatures.FINITE_DASH in features)
          assertFalse(KetchFeatures.FINITE_MEDIA in features)
          val url = "http://127.0.0.1:${server.address.port}/redirect/$manifest"
          val resolved = if (legacy) ketch.resolve(url).copy(sourceType = "media") else null
          val task = ketch.download(DownloadRequest(
            url = url,
            resolvedSource = resolved,
            destination = Destination(directory.absolutePath + "/"),
          ))
          val state = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
          val expected = parts.fold(byteArrayOf()) { bytes, part -> bytes + resource(part) }
          val file = File(state.outputPath)
          assertContentEquals(expected, file.readBytes())
          assertEquals(expected.size.toLong(), state.totalBytes)
          assertEquals(if (manifest.endsWith("m3u8")) "ts" else "mp4", file.extension)
        } finally {
          ketch.close()
          engine.close()
        }
      }
    } finally {
      server.stop(0)
      directory.deleteRecursively()
    }
  }
}
