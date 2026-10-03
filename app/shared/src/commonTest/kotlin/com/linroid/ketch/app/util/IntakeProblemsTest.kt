package com.linroid.ketch.app.util

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.util.IntakeAction.AddAnyway
import com.linroid.ketch.app.util.IntakeAction.AddHeaders
import com.linroid.ketch.app.util.IntakeAction.FindMirror
import com.linroid.ketch.app.util.IntakeAction.KeepWaiting
import com.linroid.ketch.app.util.IntakeAction.PasteCurl
import com.linroid.ketch.app.util.IntakeAction.Retry
import com.linroid.ketch.app.util.IntakeAction.SignIn
import kotlin.test.Test
import kotlin.test.assertEquals

class IntakeProblemsTest {
  private val url = "https://user:secret@files.example.com:8443/q3-report.pdf?token=abc"

  @Test
  fun toIntakeProblem_torrentsOrFiles_sayWhatTheDeviceCantDo() {
    val noTorrents = IntakeProblem("This device can't download torrents")
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    assertEquals(noTorrents, KetchError.Unsupported().toIntakeProblem(magnet))
    assertEquals(noTorrents, KetchError.Unsupported().toIntakeProblem("a.torrent", file = true))
    assertEquals(
      IntakeProblem("Couldn't read this file", "too large"),
      IllegalArgumentException("too large").toIntakeProblem("a.torrent", file = true),
    )
    assertEquals(
      IntakeProblem("Couldn't read this torrent", actions = listOf(Retry)),
      KetchError.SourceError("torrent").toIntakeProblem("a.torrent", file = true),
    )
  }

  @Test
  fun toIntakeProblem_eachFailure_matchesTheCatalog() {
    val cases = listOf(
      KetchError.Unsupported() to IntakeProblem(
        "Ketch can't download this kind of link",
        "it supports http(s), ftp(s), magnet and .torrent",
      ),
      KetchError.Http(401) to IntakeProblem("Sign-in required", actions = listOf(SignIn)),
      KetchError.AuthenticationFailed("ftp") to
        IntakeProblem("Sign-in required", actions = listOf(SignIn)),
      KetchError.Http(403, "Forbidden") to IntakeProblem(
        "The server refused access (403)",
        "links copied from a signed-in page often need its cookies",
        listOf(PasteCurl, AddHeaders),
      ),
      KetchError.Http(404) to IntakeProblem("Not found", "the link may have expired"),
      KetchError.Http(410) to IntakeProblem("Not found", "the link may have expired"),
      KetchError.Http(429) to
        IntakeProblem("Server busy (429)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(503) to
        IntakeProblem("Server busy (503)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(500) to
        IntakeProblem("Server busy (500)", "Ketch will retry automatically", blocksAdd = false),
      KetchError.Http(418, "I'm a teapot") to
        IntakeProblem("The server answered 418", "I'm a teapot", listOf(Retry)),
      KetchError.Http(400, " ") to IntakeProblem("The server answered 400", null, listOf(Retry)),
      KetchError.Network() to
        IntakeProblem("Can't reach files.example.com", actions = listOf(Retry)),
      KetchError.SourceError("torrent") to
        IntakeProblem("Couldn't read this torrent", actions = listOf(Retry)),
      KetchError.SourceError("ftp") to
        IntakeProblem("The FTP server reported an error", actions = listOf(Retry)),
      KetchError.SourceError("hls") to
        IntakeProblem("Couldn't check this link", actions = listOf(Retry)),
      KetchError.Unknown(errorMessage = "boom") to
        IntakeProblem("Couldn't check this link", "boom", listOf(Retry)),
      KetchError.Disk() to IntakeProblem("Couldn't check this link", actions = listOf(Retry)),
      IllegalStateException("Device went away") to
        IntakeProblem("Couldn't check this link", "Device went away", listOf(Retry)),
    )
    cases.forEach { (error, problem) ->
      assertEquals(problem, error.toIntakeProblem(url), error.toString())
    }
  }

  @Test
  fun toIntakeProblem_missingFileWithDiscover_offersAMirror() {
    val problem = KetchError.Http(404).toIntakeProblem(url, discoverAvailable = true)

    assertEquals(listOf(FindMirror), problem.actions)
    assertEquals("Not found · the link may have expired", problem.text)
  }

  @Test
  fun toIntakeProblem_networkFailure_namesTheHost() {
    val cases = mapOf(
      "ftp://ftp.example.org/pub/a.iso" to "Can't reach ftp.example.org",
      "http://[fd00::20]:8642/a" to "Can't reach [fd00::20]",
      "https://user@example.com?mail=me@x.org" to "Can't reach example.com",
      "magnet:?xt=urn:btih:abc" to "Can't reach any peers",
      "not a url" to "Can't reach the server",
    )
    cases.forEach { (link, title) ->
      assertEquals(title, KetchError.Network().toIntakeProblem(link).title, link)
    }
  }

  @Test
  fun magnetTimeout_afterTwoMinutes_offersToWaitOrAdd() {
    assertEquals(
      IntakeProblem(
        "No peers sent the file list in 2 min",
        actions = listOf(KeepWaiting, AddAnyway),
      ),
      IntakeProblem.MagnetTimeout,
    )
    assertEquals("Sign-in required", IntakeProblem("Sign-in required").text)
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
}
