package com.linroid.ketch.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebResourcesTest {

  private val dir = createTempDirectory("web").toFile()
  private val script = "console.log('ketch')".repeat(100)

  init {
    File(dir, "web").mkdirs()
    File(dir, "web/index.html").writeText("<html></html>")
    GZIPOutputStream(File(dir, "web/app.js.gz").outputStream()).use {
      it.write(script.toByteArray())
    }
  }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun resource(name: String): URL? =
    File(dir, name).takeIf { it.isFile }?.toURI()?.toURL()

  @Test
  fun gzippedFile_clientAcceptsGzip_isSentCompressed() = testApplication {
    routing { webResources(::resource) }

    val response = client.get("/app.js") { header(HttpHeaders.AcceptEncoding, "br, gzip") }

    assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
    assertEquals(HttpHeaders.AcceptEncoding, response.headers[HttpHeaders.Vary])
    assertEquals(ContentType.Text.JavaScript, response.contentType()?.withoutParameters())
    val unpacked = GZIPInputStream(response.bodyAsBytes().inputStream()).use { it.readBytes() }
    assertContentEquals(script.toByteArray(), unpacked)
  }

  @Test
  fun gzippedFile_clientRefusesGzip_isSentUnpacked() = testApplication {
    routing { webResources(::resource) }

    val response = client.get("/app.js") { header(HttpHeaders.AcceptEncoding, "gzip;q=0") }

    assertNull(response.headers[HttpHeaders.ContentEncoding])
    assertEquals(script, response.bodyAsText())
  }

  @Test
  fun gzippedFile_gzipRefusedButWildcardAccepted_isSentUnpacked() = testApplication {
    routing { webResources(::resource) }

    val response = client.get("/app.js") { header(HttpHeaders.AcceptEncoding, "gzip;q=0, *;q=1") }

    assertNull(response.headers[HttpHeaders.ContentEncoding])
    assertEquals(script, response.bodyAsText())
  }

  @Test
  fun gzippedFile_onlyWildcardAccepted_isSentCompressed() = testApplication {
    routing { webResources(::resource) }

    val response = client.get("/app.js") { header(HttpHeaders.AcceptEncoding, "*") }

    assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
  }

  @Test
  fun plainFile_isSentAsItIs() = testApplication {
    routing { webResources(::resource) }

    val response = client.get("/") { header(HttpHeaders.AcceptEncoding, "gzip") }

    assertNull(response.headers[HttpHeaders.ContentEncoding])
    assertEquals("<html></html>", response.bodyAsText())
  }
}
