package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentRevision
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteKetchTest {
  private fun status(features: Set<String>) = KetchStatus(
    name = "remote",
    version = "1",
    revision = "r",
    uptime = 0,
    config = DownloadConfig(),
    system = SystemInfo(
      os = "Linux", arch = "x64", separator = "/", javaVersion = "21", availableProcessors = 4,
      maxMemory = 0, totalMemory = 0, freeMemory = 0, downloadDirectory = "/data",
      totalSpace = 0, freeSpace = 0, usableSpace = 0,
    ),
    features = features,
  )

  @Test
  fun download_awaitFlagOnOldServer_failsBeforePost() = runTest {
    val paths = mutableListOf<String>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      paths += request.url.encodedPath
      respond(
        Json.encodeToString(status(features = setOf(KetchFeatures.TORRENT_FILE_SELECTION))),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    })
    try {
      assertFailsWith<UnsupportedOperationException> {
        remote.download(DownloadRequest("magnet:?xt=urn:btih:00", awaitFileSelection = true))
      }
      assertFailsWith<UnsupportedOperationException> {
        remote.download(DownloadRequest("magnet:?xt=urn:btih:01", awaitFileSelection = true))
      }
      // The features were asked once for this connection, and nothing was added.
      assertEquals(listOf("/api/status"), paths)
    } finally {
      remote.close()
    }
  }

  @Test
  fun torrents_oldServer_failCommandsClosed() = runTest {
    val paths = mutableListOf<String>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      paths += request.url.encodedPath
      respond("", HttpStatusCode.NotFound)
    })
    try {
      val error = assertFailsWith<TorrentCommandException> {
        remote.torrents.select(
          "t1", setOf("0"), TorrentCommandContext("k", TorrentRevision("e", 0)),
        )
      }
      assertEquals(TorrentCommandError.UNSUPPORTED, error.error)
      assertEquals(listOf("/api/torrents/capabilities"), paths)
    } finally {
      remote.close()
    }
  }
}
