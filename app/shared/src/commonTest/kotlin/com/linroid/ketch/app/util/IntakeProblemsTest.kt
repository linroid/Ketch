package com.linroid.ketch.app.util

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.util.IntakeAction.AddAnyway
import com.linroid.ketch.app.util.IntakeAction.AddHeaders
import com.linroid.ketch.app.util.IntakeAction.FindMirror
import com.linroid.ketch.app.util.IntakeAction.KeepWaiting
import com.linroid.ketch.app.util.IntakeAction.PasteCurl
import com.linroid.ketch.app.util.IntakeAction.Retry
import com.linroid.ketch.app.util.IntakeAction.SignIn
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IntakeProblemsTest {
  private val url = "https://user:secret@files.example.com:8443/q3-report.pdf?token=abc"

  @Test
  fun toIntakeProblem_torrentsOrFiles_sayWhatTheDeviceCantDo() = runTest {
    val noTorrents = Expected("This device can't download torrents")
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    assertEquals(noTorrents, KetchError.Unsupported().toIntakeProblem(magnet).loaded())
    assertEquals(
      noTorrents,
      KetchError.Unsupported().toIntakeProblem("a.torrent", file = true).loaded(),
    )
    assertEquals(
      Expected("Couldn't read this file", "too large"),
      IllegalArgumentException("too large").toIntakeProblem("a.torrent", file = true).loaded(),
    )
    assertEquals(
      Expected("Couldn't read this torrent", actions = listOf(Retry)),
      KetchError.SourceError("torrent").toIntakeProblem("a.torrent", file = true).loaded(),
    )
  }

  @Test
  fun toIntakeProblem_eachFailure_matchesTheCatalog() = runTest {
    val cases = listOf(
      KetchError.Unsupported() to Expected(
        "Ketch can't download this kind of link",
        "it supports http(s), ftp(s), magnet and .torrent",
      ),
      KetchError.Http(401) to Expected("Sign-in required", actions = listOf(SignIn)),
      KetchError.AuthenticationFailed("ftp") to
        Expected("Sign-in required", actions = listOf(SignIn)),
      KetchError.Http(403, "Forbidden") to Expected(
        "The server refused access (403)",
        "links copied from a signed-in page often need its cookies",
        listOf(PasteCurl, AddHeaders),
      ),
      KetchError.Http(404) to Expected("Not found", "the link may have expired"),
      KetchError.Http(410) to Expected("Not found", "the link may have expired"),
      KetchError.Http(429) to
        Expected("Server busy (429)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(503) to
        Expected("Server busy (503)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(500) to
        Expected("Server busy (500)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(418, "I'm a teapot") to
        Expected("The server answered 418", "I'm a teapot", listOf(Retry)),
      KetchError.Http(400, " ") to Expected("The server answered 400", null, listOf(Retry)),
      KetchError.Network() to
        Expected("Can't reach files.example.com", actions = listOf(Retry)),
      KetchError.SourceError("torrent") to
        Expected("Couldn't read this torrent", actions = listOf(Retry)),
      KetchError.SourceError("ftp") to
        Expected("The FTP server reported an error", actions = listOf(Retry)),
      KetchError.SourceError("hls") to
        Expected("Couldn't check this link", actions = listOf(Retry)),
      KetchError.Unknown(errorMessage = "boom") to
        Expected("Couldn't check this link", "boom", listOf(Retry)),
      KetchError.Disk() to Expected("Couldn't check this link", actions = listOf(Retry)),
      IllegalStateException("Device went away") to
        Expected("Couldn't check this link", "Device went away", listOf(Retry)),
    )
    cases.forEach { (error, expected) ->
      assertEquals(expected, error.toIntakeProblem(url).loaded(), error.toString())
    }
  }

  @Test
  fun toIntakeProblem_missingFileWithDiscover_offersAMirror() = runTest {
    val problem = KetchError.Http(404).toIntakeProblem(url, discoverAvailable = true)

    assertEquals(listOf(FindMirror), problem.actions)
    assertEquals("Not found · the link may have expired", problem.text.load())
  }

  @Test
  fun toIntakeProblem_networkFailure_namesTheHost() = runTest {
    val cases = mapOf(
      "ftp://ftp.example.org/pub/a.iso" to "Can't reach ftp.example.org",
      "http://[fd00::20]:8642/a" to "Can't reach [fd00::20]",
      "https://user@example.com?mail=me@x.org" to "Can't reach example.com",
      "magnet:?xt=urn:btih:abc" to "Can't reach any peers",
      "not a url" to "Can't reach the server",
    )
    cases.forEach { (link, title) ->
      assertEquals(title, KetchError.Network().toIntakeProblem(link).title.load(), link)
    }
  }

  @Test
  fun magnetTimeout_afterTwoMinutes_offersToWaitOrAdd() = runTest {
    assertEquals(
      Expected("No peers sent the file list in 2 min", actions = listOf(KeepWaiting, AddAnyway)),
      IntakeProblem.MagnetTimeout.loaded(),
    )
    val signIn = KetchError.Http(401).toIntakeProblem(url)
    assertEquals("Sign-in required", signIn.text.load())
  }

  @Test
  fun withCredentials_httpLink_sendsBasicAuthorization() {
    val link = IntakeItem.Link(
      "https://example.com/a.zip",
      mapOf("authorization" to "Bearer old", "Cookie" to "a=b"),
    )

    assertEquals(
      IntakeItem.Link(
        "https://example.com/a.zip",
        mapOf("Cookie" to "a=b", "Authorization" to "Basic dXNlcjpwYXNz"),
      ),
      link.withCredentials("user", "pass"),
    )
  }

  @Test
  fun withCredentials_ftpLink_embedsThemInTheUrl() {
    val userInfo = "bj%C3%B6rn:p%40ss%3Aw%2F%3F@"
    val cases = mapOf(
      "ftp://ftp.example.org/pub/a.iso" to "ftp://${userInfo}ftp.example.org/pub/a.iso",
      "ftps://old:pw@ftp.example.org" to "ftps://${userInfo}ftp.example.org",
    )
    cases.forEach { (url, expected) ->
      val signedIn = IntakeItem.Link(url).withCredentials("björn", "p@ss:w/?")
      assertEquals(IntakeItem.Link(expected), signedIn, url)
    }
  }

  /** A problem as the user reads it. */
  private data class Expected(
    val title: String,
    val detail: String? = null,
    val actions: List<IntakeAction> = emptyList(),
    val blocksAdd: Boolean = true,
  )

  private suspend fun IntakeProblem.loaded() =
    Expected(title.load(), detail.load(), actions, blocksAdd)
}
