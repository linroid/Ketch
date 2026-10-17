package com.linroid.ketch.server

import com.linroid.ketch.api.CountryDatabase
import com.linroid.ketch.api.CountryDatabaseStatus
import com.linroid.ketch.api.CountryDatabaseStatus.State
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.geo.CountryLookup
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A server's country database, downloaded on the server at a client's request. */
class RemoteCountriesTest {

  /** Answers a ranged file whose requests never finish. */
  private class StalledEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>) = ServerInfo(
      contentLength = 1024 * 1024,
      acceptRanges = true,
      etag = "\"v1\"",
      lastModified = null,
    )

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      onData(ByteArray(1024))
      awaitCancellation()
    }

    override fun close() {}
  }

  /** Knows `files.example` is in Germany once downloaded. */
  private class FakeCountries : CountryLookup {
    @Volatile
    var state = State.MISSING
    var downloads = 0

    override suspend fun status() = CountryDatabaseStatus(state)

    override suspend fun download(): CountryDatabaseStatus {
      downloads++
      state = State.READY
      return CountryDatabaseStatus(State.DOWNLOADING)
    }

    override fun countryOf(host: String): String? =
      "DE".takeIf { state == State.READY && host == "files.example" }
  }

  @Test
  fun remote_downloadsTheServersDatabase_andSeesItsCountries() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val folder = Files.createTempDirectory("ketch-remote-countries").toFile()
        val countries = FakeCountries()
        val ketch = Ketch(httpEngine = StalledEngine(), countries = countries)
        val server = KetchServer(
          ketch,
          port = 0,
          allowedDirectories = listOf(folder.path),
          mdnsEnabled = false,
        )
        var remote: RemoteKetch? = null
        try {
          ketch.start()
          server.start(wait = false)
          val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
          assertTrue(KetchFeatures.COUNTRIES in client.status().features)
          val database = assertNotNull(client.controller(CountryDatabase))

          assertEquals(State.MISSING, database.status().state)
          assertEquals(State.DOWNLOADING, database.download().state)
          assertEquals(1, countries.downloads)
          assertEquals(State.READY, database.status().state)

          ketch.download(
            DownloadRequest(
              url = "https://files.example/big.bin",
              destination = Destination(folder.path + "/"),
              connections = 1,
            ),
          )
          val open = client.subscribe(Interest.Connections()).first { it.total == 1 }
          assertEquals("DE", open.connections.single().country)
        } finally {
          remote?.close()
          server.stop()
          ketch.close()
          folder.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun remote_withoutADatabase_failsClosed() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val ketch = Ketch(httpEngine = StalledEngine())
        val server = KetchServer(ketch, port = 0, mdnsEnabled = false)
        var remote: RemoteKetch? = null
        try {
          ketch.start()
          server.start(wait = false)
          val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
          val database = assertNotNull(client.controller(CountryDatabase))
          assertFailsWith<UnsupportedOperationException> { database.download() }
        } finally {
          remote?.close()
          server.stop()
          ketch.close()
        }
      }
    }
  }
}
