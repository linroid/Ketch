package com.linroid.ketch.torrent

import com.linroid.ketch.engine.KtorHttpEngine
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TorrentTlsTest {
  @Test
  fun httpsMetainfoAndTracker_validateTrustAndUseRealTls() = runTest {
    withContext(Dispatchers.IO) {
      val root = Files.createTempDirectory("ketch-tls").toFile()
      val password = "fixture-only".toCharArray()
      val keyStoreFile = root.resolve("fixture.p12")
      val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool").absolutePath
      val generated = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
        "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
        "-keystore", keyStoreFile.absolutePath, "-storepass", String(password), "-noprompt")
        .redirectErrorStream(true).redirectOutput(root.resolve("keytool.log")).start()
      check(generated.waitFor(15, TimeUnit.SECONDS) && generated.exitValue() == 0)
      val keys = KeyStore.getInstance("PKCS12").apply {
        keyStoreFile.inputStream().use { load(it, password) }
      }
      val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keys, password) }
      val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
      val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.httpsConfigurator = HttpsConfigurator(ssl)
      val metainfo = Bencode.encode(mapOf("info" to mapOf("name" to "empty", "length" to 0L,
        "piece length" to 16_384L, "pieces" to ByteArray(0))))
      server.createContext("/fixture.torrent") { exchange ->
        exchange.sendResponseHeaders(200, metainfo.size.toLong())
        exchange.responseBody.use { it.write(metainfo) }
      }
      val response = Bencode.encode(mapOf("interval" to 30L, "peers" to ByteArray(0)))
      server.createContext("/announce") { exchange ->
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
      }
      server.start()
      val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keys) }.trustManagers.filterIsInstance<X509TrustManager>().single()
      val borrowed = KtorHttpEngine(HttpClient(CIO) { engine { https { trustManager = trust } } },
        logRequests = false)
      val source = TorrentDownloadSource(httpEngine = borrowed)
      val untrusted = TorrentDownloadSource()
      val network = createTorrentNetwork()
      val url = "https://localhost:${server.address.port}"
      try {
        assertEquals(0L, source.resolve("$url/fixture.torrent", emptyMap()).totalBytes)
        val tracker = TorrentTracker(TorrentHttp(borrowed), network)
        val metadata = TorrentMetadata.fromBencode(metainfo)
        assertEquals(emptyList(), tracker.announce("$url/announce",
          TrackerAnnounce(metadata.infoHash, torrentRandomBytes(20), 6881, 0, 0)).peers)
        assertFails { untrusted.resolve("$url/fixture.torrent", emptyMap()) }
      } finally {
        source.close()
        untrusted.close()
        borrowed.close()
        network.close()
        server.stop(0)
        root.deleteRecursively()
      }
    }
  }
}
