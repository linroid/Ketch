package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Real public-swarm download; enable with -PpublicTorrentTests=true.
 *
 * Uses the current Debian netinst image so the swarm stays well seeded across point releases,
 * and verifies the payload against Debian's published SHA256SUMS. The magnet adds a UDP tracker
 * beside Debian's HTTP tracker because some networks block one or the other.
 */
class PublicSwarmTest {
  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

  @Test
  fun debianNetinst_pauseResume_matchesPublishedChecksum() = runTest(timeout = 20.minutes) {
    // Real I/O and peer timeouts must use wall-clock time, not runTest's virtual clock.
    withContext(Dispatchers.IO) {
      val index = String(fetch(DEBIAN_BT_CD))
      val name = checkNotNull(NETINST.find(index)?.groupValues?.get(1)) {
        "No netinst torrent listed at $DEBIAN_BT_CD"
      }
      val expected = String(fetch("${DEBIAN_BT_CD}SHA256SUMS")).lineSequence()
        .map { it.split(Regex("\\s+")) }
        .first { it.size == 2 && it[1] == name }[0]
      val infoHash = TorrentMetadata.fromBencode(fetch("$DEBIAN_BT_CD$name.torrent")).infoHash
      val magnet = "magnet:?xt=urn:btih:${infoHash.hex}&dn=$name" +
        TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, Charsets.UTF_8) }

      val directory = Files.createTempDirectory("ketch-public-swarm-").toFile()
      val ketch = Ketch(
        httpEngine = KtorHttpEngine(),
        config = DownloadConfig(progressIntervalMs = 200),
        additionalSources = listOf(TorrentDownloadSource(TorrentConfig())),
      )
      try {
        val task = ketch.download(
          DownloadRequest(url = magnet, destination = Destination(directory.path + File.separator)),
        )
        val downloading = task.state.first {
          it.isTerminal || it is DownloadState.Downloading && it.progress.downloadedBytes > 0
        }
        assertIs<DownloadState.Downloading>(downloading, "Download ended as $downloading")
        task.pause()
        val paused = task.state.first { it is DownloadState.Paused || it.isTerminal }
        assertIs<DownloadState.Paused>(paused, "Pause ended as $paused")
        assertTrue(paused.progress.downloadedBytes < paused.progress.totalBytes)
        task.resume()

        val state = task.state.first { it.isTerminal }
        val completed = assertIs<DownloadState.Completed>(state, "Download ended as $state")
        val file = File(completed.outputPath)
        assertEquals(name, file.name)
        assertEquals(expected, sha256(file))
      } finally {
        ketch.close()
        directory.deleteRecursively()
      }
    }
  }

  private fun fetch(url: String): ByteArray {
    val response = http.send(
      HttpRequest.newBuilder(URI(url)).build(),
      HttpResponse.BodyHandlers.ofByteArray(),
    )
    check(response.statusCode() == 200) { "GET $url returned ${response.statusCode()}" }
    return response.body()
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 20)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private companion object {
    const val DEBIAN_BT_CD = "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/"
    val NETINST = Regex("href=\"(debian-[0-9.]+-amd64-netinst\\.iso)\\.torrent\"")
    val TRACKERS = listOf(
      "http://bttracker.debian.org:6969/announce",
      "udp://tracker.opentrackr.org:1337/announce",
    )
  }
}
