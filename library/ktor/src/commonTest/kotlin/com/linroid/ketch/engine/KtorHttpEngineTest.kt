package com.linroid.ketch.engine

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.engine.HttpBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KtorHttpEngineTest {
  @Test
  fun download_ignoredPartialRange_rejectsBeforeDeliveringData() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_ignoredFirstSegment_rejectsBeforeDeliveringData() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 0L..3L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_fullFileWithoutRangeSupport_succeeds() = runTest {
    withResponse(HttpStatusCode.OK, "abcdefgh", "Content-Length" to "8") { engine ->
      var body = ""
      engine.download("https://example.com/file", 0L..7L) { body += it.decodeToString() }
      assertEquals("abcdefgh", body)
    }
  }

  @Test
  fun download_chunkedFullResponse_validatesActualLength() = runTest {
    val chunked = "Transfer-Encoding" to "chunked"
    withResponse(HttpStatusCode.OK, "abcdefgh", chunked) { engine ->
      var body = ""
      engine.download("https://example.com/file", 0L..7L) { body += it.decodeToString() }
      assertEquals("abcdefgh", body)
    }
    withResponse(HttpStatusCode.OK, "abcd", chunked) { engine ->
      assertFailsWith<KetchError.Network> {
        engine.download("https://example.com/file", 0L..7L) {}
      }
    }
    withResponse(HttpStatusCode.OK, "abcdefghij", chunked) { engine ->
      var delivered = 0
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 0L..7L) { delivered += it.size }
      }
      assertTrue(delivered <= 8)
    }
    withResponse(HttpStatusCode.OK, "abcdefgh", chunked) { engine ->
      var delivered = false
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered = true }
      }
      assertTrue(!delivered)
    }
  }

  @Test
  fun download_invalidContentRange_rejectsBeforeDeliveringData() = runTest {
    for (range in listOf("", "bytes 0-3/8", "bytes 4-6/8", "bytes 4-7/7", "invalid")) {
      withResponse(HttpStatusCode.PartialContent, "efgh", "Content-Range" to range) { engine ->
        var delivered = false
        assertFailsWith<KetchError.Unsupported>(range) {
          engine.download("https://example.com/file", 4L..7L) { delivered = true }
        }
        assertTrue(!delivered, range)
      }
    }
  }

  @Test
  fun download_validContentRange_deliversRequestedBytes() = runTest {
    val contentRange = "Content-Range" to "bytes 4-7/8"
    withResponse(HttpStatusCode.PartialContent, "efgh", contentRange) { engine ->
      var body = ""
      engine.download("https://example.com/file", 4L..7L) { body += it.decodeToString() }
      assertEquals("efgh", body)
    }
  }

  @Test
  fun download_truncatedRange_fails() = runTest {
    withResponse(HttpStatusCode.PartialContent, "ef", "Content-Range" to "bytes 4-7/8") { engine ->
      assertFailsWith<KetchError.Network> {
        engine.download("https://example.com/file", 4L..7L) {}
      }
    }
  }

  @Test
  fun download_oversizedRange_neverDeliversExcessBytes() = runTest {
    val contentRange = "Content-Range" to "bytes 4-7/8"
    withResponse(HttpStatusCode.PartialContent, "efghij", contentRange) { engine ->
      var delivered = 0
      assertFailsWith<KetchError.Unsupported> {
        engine.download("https://example.com/file", 4L..7L) { delivered += it.size }
      }
      assertTrue(delivered <= 4)
    }
  }

  @Test
  fun headAndDownload_sensitiveResponseHeaders_areMaskedInLogs() = runTest {
    val records = mutableListOf<String>()
    KetchLogger.setLogger(object : Logger {
      override fun v(message: String) {}
      override fun d(message: String) {
        records += message
      }
      override fun i(message: String) {}
      override fun w(message: String, throwable: Throwable?) {}
      override fun e(message: String, throwable: Throwable?) {}
    })
    val engine = KtorHttpEngine(HttpClient(MockEngine {
      respond(
        "abcdefgh",
        HttpStatusCode.OK,
        headersOf("Content-Length" to listOf("8"), "Set-Cookie" to listOf("session=s3cr3t")),
      )
    }))
    try {
      engine.head("https://example.com/file", emptyMap())
      engine.download("https://example.com/file", 0L..7L) {}
    } finally {
      engine.close()
      KetchLogger.setLogger(Logger.None)
    }

    val headerLines = listOf("HEAD 200 headers:", "GET 200 headers:").map { prefix ->
      records.single { prefix in it }
    }
    headerLines.forEach { line ->
      assertTrue(line.contains("Set-Cookie=***", ignoreCase = true), line)
      assertFalse("s3cr3t" in line, line)
    }
  }

  @Test
  fun requests_withoutUserAgent_sendKetchUserAgent() = runTest {
    val length = headersOf(HttpHeaders.ContentLength, "8")
    withServer({ respond("abcdefgh", HttpStatusCode.OK, length) }) { engine, requests ->
      engine.head(URL, emptyMap())
      engine.download(URL, null, mapOf("user-agent" to "Browser/1.0")) {}

      assertEquals(listOf("Ketch/${KetchApi.VERSION}"), requests[0].headers.getAll(USER_AGENT))
      assertEquals(listOf("Browser/1.0"), requests[1].headers.getAll(USER_AGENT))
    }
  }

  @Test
  fun requests_nullUserAgent_sendNone() = runTest {
    withServer({ respond("", HttpStatusCode.OK) }, userAgent = null) { engine, requests ->
      engine.head(URL, emptyMap())

      assertNull(requests.single().headers[USER_AGENT])
    }
  }

  @Test
  fun requests_engineManagedHeaders_areIgnored() = runTest {
    val contentRange = headersOf(HttpHeaders.ContentRange, "bytes 0-3/8")
    withServer({ respond("abcd", HttpStatusCode.PartialContent, contentRange) }) {
      engine, requests ->
      val headers = mapOf(
        "Range" to "bytes=0-",
        "Host" to "evil.example",
        "Connection" to "close",
        "Content-Length" to "0",
      )
      engine.download(URL, 0L..3L, headers) {}

      val sent = requests.single().headers
      assertEquals(listOf("bytes=0-3"), sent.getAll(HttpHeaders.Range))
      assertNull(sent[HttpHeaders.Host])
      assertNull(sent[HttpHeaders.Connection])
      assertNull(sent[HttpHeaders.ContentLength])
    }
  }

  @Test
  fun requests_headerWithLineBreak_failWithoutReachingServerOrRetrying() = runTest {
    withServer({ respond("", HttpStatusCode.OK) }) { engine, requests ->
      val headers = mapOf("Cookie" to "sid=s3cr3t\r\nX-Injected: 1")

      val error = assertFailsWith<IllegalArgumentException> { engine.head(URL, headers) }

      assertFalse("s3cr3t" in error.message.orEmpty(), error.message)
      assertTrue(requests.isEmpty())
    }
  }

  @Test
  fun download_crossOriginRedirect_keepsOnlyHeadersWithoutCredentials() = runTest {
    val logged = recordLogs()
    val headers = mapOf(
      "Cookie" to "sid=s3cr3t",
      "Authorization" to "Bearer t0ken",
      "Proxy-Authorization" to "Basic pr0xy",
      "X-Api-Key" to "k3y",
      "Referer" to "https://page.example:8443/path?token=r3f",
      "Accept" to "application/octet-stream",
      "Accept-Language" to "de",
      "User-Agent" to "Browser/1.0",
    )
    try {
      withServer({ request ->
        if (request.url.host == "origin.example") {
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
        } else {
          respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
        }
      }) { engine, requests ->
        engine.download("https://user:pw@origin.example/file", null, headers) {}

        assertEquals("sid=s3cr3t", requests[0].headers[HttpHeaders.Cookie])
        val hop = requests[1]
        assertEquals("cdn.example", hop.url.host)
        assertNull(hop.url.user)
        assertNull(hop.url.password)
        for (name in listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Api-Key")) {
          assertNull(hop.headers[name], name)
        }
        assertEquals("https://page.example:8443/", hop.headers[HttpHeaders.Referrer])
        assertEquals("application/octet-stream", hop.headers[HttpHeaders.Accept])
        assertEquals("de", hop.headers[HttpHeaders.AcceptLanguage])
        assertEquals("Browser/1.0", hop.headers[USER_AGENT])
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }
    for (secret in listOf("s3cr3t", "t0ken", "pr0xy", "k3y", "r3f")) {
      assertTrue(logged.none { (_, message) -> secret in message }, secret)
    }
  }

  @Test
  fun download_sameOriginRedirect_keepsHeaders() = runTest {
    withServer({ request ->
      if (request.url.encodedPath == "/file") {
        respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/real"))
      } else {
        respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
      }
    }) { engine, requests ->
      engine.download(URL, null, mapOf("Cookie" to "sid=1")) {}

      assertEquals("/real", requests[1].url.encodedPath)
      assertEquals("sid=1", requests[1].headers[HttpHeaders.Cookie])
    }
  }

  @Test
  fun head_redirectToHttpOrOtherScheme_isRefused() = runTest {
    for (location in listOf("http://cdn.example/file", "ftp://cdn.example/file")) {
      withServer({ respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location)) }) {
        engine, requests ->
        val error = assertFailsWith<KetchError.Http>(location) { engine.head(URL, emptyMap()) }

        assertEquals(302, error.code, location)
        assertEquals(1, requests.size, location)
      }
    }
  }

  @Test
  fun head_redirectLoop_stopsAfterTwentyRedirects() = runTest {
    withServer({ respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/loop")) }) {
      engine, requests ->
      assertFailsWith<KetchError.Http> { engine.head(URL, emptyMap()) }

      assertEquals(21, requests.size)
    }
  }

  @Test
  fun download_afterRedirectedProbe_goesStraightToTarget() = runTest {
    withServer({ request ->
      if (request.url.host == "origin.example") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
      } else {
        respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
      }
    }) { engine, requests ->
      val headers = mapOf("Cookie" to "sid=1")
      engine.head(URL, headers)
      engine.download(URL, null, headers) {}
      engine.download(URL, null, headers) {}

      val sent = requests.map { "${it.method.value} ${it.url.host}" }
      assertEquals(
        listOf("HEAD origin.example", "HEAD cdn.example", "GET cdn.example", "GET cdn.example"),
        sent,
      )
      assertTrue(requests.drop(1).all { it.headers[HttpHeaders.Cookie] == null })
    }
  }

  @Test
  fun withProxy_ownClient_refusesProxiesOtherThanTheSystems() = runTest {
    withResponse(HttpStatusCode.OK, "", "Content-Length" to "0") { engine ->
      assertFalse(engine.supportsProxies)
      assertTrue(engine.withProxy(Proxy.System) === engine)
      assertFailsWith<KetchError.Unsupported> { engine.withProxy(Proxy.Direct) }
    }
  }

  @Test
  fun download_throughAnotherProxy_followsItsOwnRedirects() = runTest {
    val requests = mutableListOf<String>()
    val transports = object : HttpTransports {
      private val clients = mutableMapOf<String, HttpClient>()

      override fun supports(proxy: Proxy): Boolean = true

      override fun clientFor(url: Url, proxy: Proxy): RoutedClient {
        // Each proxy is sent to a mirror of its own.
        val name = (proxy as? Proxy.Manual)?.address?.host ?: "direct"
        val client = clients.getOrPut(name) {
          HttpClient(MockEngine { request ->
            requests += "$name ${request.method.value} ${request.url.host}"
            if (request.url.host == "origin.example") {
              val mirror = "https://mirror-$name.example/file"
              respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, mirror))
            } else {
              respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
            }
          }) { followRedirects = false }
        }
        return RoutedClient(client, ProxyRoute.Direct)
      }

      override fun close() {
        clients.values.forEach { it.close() }
      }
    }
    val engine = KtorHttpEngine(transports, logRequests = false, userAgent = null)
    try {
      val first = engine.withProxy(Proxy.Manual("http://first:3128"))
      val second = engine.withProxy(Proxy.Manual("http://second:3128"))
      first.head(URL)
      second.head(URL)
      first.download(URL, null) {}
      second.download(URL, null) {}

      assertEquals(
        listOf(
          "first HEAD origin.example", "first HEAD mirror-first.example",
          "second HEAD origin.example", "second HEAD mirror-second.example",
          "first GET mirror-first.example", "second GET mirror-second.example",
        ),
        requests,
      )
    } finally {
      engine.close()
    }
  }

  @Test
  fun download_afterProbeIsNoLongerRedirected_goesToOrigin() = runTest {
    var redirect = true
    withServer({ request ->
      if (redirect && request.url.host == "origin.example") {
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
      } else {
        respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
      }
    }) { engine, requests ->
      engine.head(URL, emptyMap())
      redirect = false
      engine.head(URL, emptyMap())
      engine.download(URL, null, emptyMap()) {}

      assertEquals(
        listOf("origin.example", "cdn.example", "origin.example", "origin.example"),
        requests.map { it.url.host },
      )
    }
  }

  @Test
  fun download_failingRedirectTarget_followsRedirectsAgain() = runTest {
    var redirects = 0
    withServer({ request ->
      when (request.url.host) {
        "origin.example" -> {
          val target = if (redirects++ == 0) CDN_URL else "https://mirror.example/file"
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target))
        }
        // The signature of the first target has expired by the time the download starts.
        "cdn.example" -> if (request.method == HttpMethod.Head) {
          respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
        } else {
          respond("", HttpStatusCode.Forbidden)
        }
        else -> respond("abcdefgh", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "8"))
      }
    }) { engine, requests ->
      engine.head(URL, emptyMap())
      var body = ""
      engine.download(URL, null, emptyMap()) { body += it.decodeToString() }

      assertEquals("abcdefgh", body)
      assertEquals(
        listOf("origin.example", "cdn.example", "cdn.example", "origin.example", "mirror.example"),
        requests.map { it.url.host },
      )
    }
  }

  @Test
  fun head_contentType_reportedForCategoryFolders() = runTest {
    val headers = headersOf(
      HttpHeaders.ContentLength to listOf("8"),
      HttpHeaders.ContentType to listOf("video/mp4; codecs=avc1"),
    )
    withServer({ respond("", HttpStatusCode.OK, headers) }) { engine, _ ->
      assertEquals("video/mp4; codecs=avc1", engine.head(URL, emptyMap()).contentType)
    }
  }

  @Test
  fun probe_partialContent_readsTotalLengthFromContentRange() = runTest {
    val headers = headersOf(
      HttpHeaders.ContentRange to listOf("bytes 0-0/1234"),
      HttpHeaders.ETag to listOf("\"v1\""),
      HttpHeaders.ContentDisposition to listOf("attachment; filename=\"a.bin\""),
    )
    withServer({ respond("a", HttpStatusCode.PartialContent, headers) }) { engine, requests ->
      val info = engine.probe(URL, emptyMap())

      assertEquals(1234, info.contentLength)
      assertTrue(info.supportsResume)
      assertEquals("\"v1\"", info.etag)
      assertEquals("attachment; filename=\"a.bin\"", info.contentDisposition)
      assertEquals(HttpMethod.Get, requests.single().method)
      assertEquals("bytes=0-0", requests.single().headers[HttpHeaders.Range])
    }
  }

  @Test
  fun probe_rangeIgnored_reportsWholeLengthWithoutRangeSupport() = runTest {
    val headers = headersOf(
      HttpHeaders.ContentLength to listOf("8"),
      HttpHeaders.AcceptRanges to listOf("bytes"),
    )
    withServer({ respond("abcdefgh", HttpStatusCode.OK, headers) }) { engine, _ ->
      val info = engine.probe(URL, emptyMap())

      assertEquals(8, info.contentLength)
      assertFalse(info.acceptRanges)
    }
  }

  @Test
  fun probe_emptyResource_reportsZeroLength() = runTest {
    // Range units ignore case.
    for (contentRange in listOf("bytes */0", "Bytes */0")) {
      val headers = headersOf(HttpHeaders.ContentRange, contentRange)
      withServer({ respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headers) }) {
        engine, _ ->
        assertEquals(0, engine.probe(URL, emptyMap()).contentLength, contentRange)
      }
    }
  }

  @Test
  fun head_refused_isNotLoggedAsError() = runTest {
    val errors = mutableListOf<String>()
    KetchLogger.setLogger(object : Logger {
      override fun v(message: String) {}
      override fun d(message: String) {}
      override fun i(message: String) {}
      override fun w(message: String, throwable: Throwable?) {}
      override fun e(message: String, throwable: Throwable?) {
        errors += message
      }
    })
    try {
      withServer({ respond("", HttpStatusCode.Forbidden) }) { engine, _ ->
        assertFailsWith<KetchError.Http> { engine.head(URL, emptyMap()) }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    assertEquals(emptyList(), errors)
  }

  @Test
  fun requests_failuresARetryMayFix_areLoggedAtDebugLevel() = runTest {
    // Callers retry them, warning as they do: an error each attempt said the same thing again.
    val records = recordLogs()
    try {
      for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests)) {
        withServer({ respond("", status) }) { engine, _ ->
          assertFailsWith<KetchError.Http> { engine.download(URL, 0L..7L) {} }
          assertFailsWith<KetchError.Http> { engine.probe(URL, emptyMap()) }
        }
      }
      withServer({ throw IllegalStateException("Connection reset") }) { engine, _ ->
        assertFailsWith<KetchError.Network> { engine.head(URL, emptyMap()) }
        assertFailsWith<KetchError.Network> { engine.download(URL, null) {} }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    assertEquals(emptyList(), records.filter { (level, _) -> level > LogLevel.DEBUG })
    val logged = records.map { (_, message) -> message }
    val failures = listOf(
      "HTTP error 503: Service Unavailable for GET $URL",
      "HTTP error 429: Too Many Requests for GET $URL",
      "IllegalStateException: Connection reset",
    )
    for (failure in failures) {
      assertEquals(2, logged.count { failure in it }, logged.joinToString("\n"))
    }
  }

  @Test
  fun download_clientError_isLoggedAsErrorWithItsUrl() = runTest {
    // No retry helps.
    val records = recordLogs()
    try {
      withServer({ respond("", HttpStatusCode.NotFound) }) { engine, _ ->
        assertFailsWith<KetchError.Http> { engine.download(URL, null) {} }
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    assertEquals(
      listOf(LogLevel.ERROR to "[KtorHttpEngine] HTTP error 404: Not Found for GET $URL"),
      records.filter { (level, _) -> level > LogLevel.DEBUG },
    )
  }

  @Test
  fun download_failsAtRedirectTarget_errorNamesTheTarget() = runTest {
    // The engine logs these at debug level only: what the caller reports when it gives up, such
    // as the Coordinator's "Download failed", says where through the error.
    var reset = false
    withServer({ request ->
      when {
        request.url.host == "origin.example" ->
          respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, CDN_URL))
        reset -> throw IllegalStateException("TLS handshake failed")
        else -> respond("", HttpStatusCode.ServiceUnavailable)
      }
    }) { engine, _ ->
      val refused = assertFailsWith<KetchError.Http> { engine.download(URL, null) {} }
      reset = true
      val failed = assertFailsWith<KetchError.Network> { engine.download(URL, null) {} }

      assertEquals(
        "Http: HTTP error 503: Service Unavailable <- FailedRequest: GET https://cdn.example/file",
        refused.describeCauses(),
      )
      // Coroutines may add a copy of the exception they recover below it.
      val causes = failed.describeCauses()
      val expected = "Network: Network error occurred <- " +
        "FailedRequest: GET https://cdn.example/file <- " +
        "IllegalStateException: TLS handshake failed"
      assertTrue(causes.startsWith(expected), causes)
    }
  }

  @Test
  fun failedRequest_urlWithCredentials_namesOnlyItsOriginAndPath() {
    // Signed links carry tokens redactUrl does not know, such as a JWT, and the message may be
    // shown to users, as an update's failure is.
    val signed = Url("https://user:pass@cdn.example:8443/a/b%20c?sig=abc&jwt=eyJ.e30.c2ln#part")
    assertEquals("GET https://cdn.example:8443/a/b%20c", FailedRequest("GET", signed).message)
    val local = Url("http://[::1]:8080/file?token=abc")
    assertEquals("HEAD http://[::1]:8080/file", FailedRequest("HEAD", local).message)
    val origin = Url("https://cdn.example")
    assertEquals("GET https://cdn.example", FailedRequest("GET", origin).message)
  }

  @Test
  fun download_callbackFails_isTheCauseOfItsError() = runTest {
    // Callers look for what their callback threw, such as GitHubReleases for its UpdateException.
    val thrown = IllegalStateException("No space left on device")
    withServer({ respond("abcdefgh", HttpStatusCode.OK) }) { engine, _ ->
      val error = assertFailsWith<KetchError.Network> {
        engine.download(URL, null) { throw thrown }
      }

      assertSame(thrown, error.cause)
    }
  }

  @Test
  fun requests_withoutRequestLogging_leaveTheUrlOutOfTheirErrors() = runTest {
    // As a torrent's trackers are asked: their URLs may hold a passkey.
    withServer({ respond("", HttpStatusCode.ServiceUnavailable) }, logRequests = false) {
      engine, _ ->
      val refused = assertFailsWith<KetchError.Http> { engine.download(URL, null) {} }

      assertNull(refused.cause)
    }
    withServer({ throw IllegalStateException("Connection reset") }, logRequests = false) {
      engine, _ ->
      val failed = assertFailsWith<KetchError.Network> { engine.download(URL, null) {} }

      assertIs<IllegalStateException>(failed.cause)
    }
  }

  @Test
  fun probe_refused_throwsHttpError() = runTest {
    withServer({ respond("", HttpStatusCode.Forbidden) }) { engine, _ ->
      val error = assertFailsWith<KetchError.Http> { engine.probe(URL, emptyMap()) }

      assertEquals(403, error.code)
    }
  }

  /** Sends every log record, with its level, to the returned list until the logger is reset. */
  private fun recordLogs(): List<Pair<LogLevel, String>> {
    val records = mutableListOf<Pair<LogLevel, String>>()
    KetchLogger.setLogger(object : Logger {
      override fun v(message: String) {
        records += LogLevel.VERBOSE to message
      }
      override fun d(message: String) {
        records += LogLevel.DEBUG to message
      }
      override fun i(message: String) {
        records += LogLevel.INFO to message
      }
      override fun w(message: String, throwable: Throwable?) {
        records += LogLevel.WARN to message
      }
      override fun e(message: String, throwable: Throwable?) {
        records += LogLevel.ERROR to message
      }
    })
    return records
  }

  @Test
  fun request_sendsMethodBodyAndHeaders() = runTest {
    withServer({ request ->
      respond(
        "<multistatus/>",
        HttpStatusCode.MultiStatus,
        headersOf("ETag" to listOf("\"a\""), "DAV" to listOf("1", "2")),
      )
    }) { engine, requests ->
      val reply = engine.request(
        "PROPFIND",
        "https://example.com/dav/file",
        mapOf("Depth" to "0", "Content-Type" to "text/plain"),
        HttpBody("<propfind/>".encodeToByteArray(), "application/xml; charset=utf-8"),
      )
      assertEquals(207, reply.status)
      assertTrue(reply.isSuccess)
      assertEquals("<multistatus/>", reply.body.decodeToString())
      assertEquals("\"a\"", reply.header("etag"))
      assertEquals(listOf("1", "2"), reply.headers("Dav"))
      val sent = requests.single()
      assertEquals("PROPFIND", sent.method.value)
      assertEquals("0", sent.headers["Depth"])
      assertEquals("application/xml; charset=utf-8", sent.body.contentType.toString())
      val content = sent.body as io.ktor.http.content.OutgoingContent.ByteArrayContent
      assertEquals("<propfind/>", content.bytes().decodeToString())
    }
  }

  @Test
  fun request_errorStatus_returnsHeadersWithoutBody() = runTest {
    withServer({
      respond(
        "denied",
        HttpStatusCode.Unauthorized,
        headersOf(
          HttpHeaders.WWWAuthenticate to listOf("Basic realm=\"a\"", "Digest realm=\"a\""),
        ),
      )
    }) { engine, _ ->
      val reply = engine.request("PROPFIND", "https://example.com/dav/file")
      assertEquals(401, reply.status)
      assertFalse(reply.isSuccess)
      assertEquals(0, reply.body.size)
      assertEquals(2, reply.headers("WWW-Authenticate").size)
    }
  }

  @Test
  fun request_followsRedirectWithSameMethod() = runTest {
    withServer({ request ->
      if (request.url.encodedPath == "/dav/dir") {
        respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/dav/dir/"))
      } else {
        respond("ok", HttpStatusCode.MultiStatus)
      }
    }) { engine, requests ->
      val reply = engine.request("PROPFIND", "https://example.com/dav/dir")
      assertEquals(207, reply.status)
      assertEquals(listOf("PROPFIND", "PROPFIND"), requests.map { it.method.value })
      assertEquals("/dav/dir/", requests.last().url.encodedPath)
      assertEquals("https://example.com/dav/dir/", reply.url)
    }
  }

  @Test
  fun request_longBody_fails() = runTest {
    withServer({ respond("abcdefgh", HttpStatusCode.OK) }) { engine, _ ->
      assertFailsWith<KetchError.Unsupported> {
        engine.request("PROPFIND", "https://example.com/file", maxBodyBytes = 4)
      }
      assertEquals(
        "abcdefgh",
        engine.request("PROPFIND", "https://example.com/file", maxBodyBytes = 8)
          .body.decodeToString(),
      )
    }
  }

  @Test
  fun request_invalidMethod_isRefused() = runTest {
    withServer({ respond("", HttpStatusCode.OK) }) { engine, requests ->
      assertFailsWith<IllegalArgumentException> {
        engine.request("GET /x", "https://example.com/file")
      }
      assertTrue(requests.isEmpty())
    }
  }

  /** Runs [block] against an engine whose requests [handler] answers and records. */
  private suspend fun withServer(
    handler: MockRequestHandler,
    userAgent: String? = KtorHttpEngine.DEFAULT_USER_AGENT,
    logRequests: Boolean = true,
    block: suspend (KtorHttpEngine, List<HttpRequestData>) -> Unit,
  ) {
    val requests = mutableListOf<HttpRequestData>()
    val client = HttpClient(MockEngine { request ->
      requests += request
      handler(request)
    })
    val engine = KtorHttpEngine(client, logRequests, userAgent)
    try {
      block(engine, requests)
    } finally {
      engine.close()
    }
  }

  private suspend fun withResponse(
    status: HttpStatusCode,
    body: String,
    header: Pair<String, String>,
    block: suspend (KtorHttpEngine) -> Unit,
  ) {
    val engine = KtorHttpEngine(HttpClient(MockEngine {
      respond(body, status, headersOf(header.first, header.second))
    }))
    try {
      block(engine)
    } finally {
      engine.close()
    }
  }

  private companion object {
    const val URL = "https://origin.example/file"
    const val CDN_URL = "https://cdn.example/file?sig=abc"
    const val USER_AGENT = "User-Agent"
  }
}
