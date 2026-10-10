package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProxyDownloadTest {
  private val content = ByteArray(65539) { (it * 7).toByte() }
  private val basic = "Basic " + Base64.getEncoder().encodeToString("me:secret".toByteArray())

  @Test
  fun httpProxy_plainRequest_sendsCredentialsToProxyOnly() = runTest(timeout = 20.seconds) {
    OriginServer(content).use { origin ->
      TestHttpProxy("me", "secret").use { proxy ->
        withEngine(ProxyConfig.manual("http://me:secret@127.0.0.1:${proxy.port}")) { engine ->
          val url = "http://origin.test:${origin.port}/file"
          assertContentEquals(content, engine.fetch(url))

          assertEquals(listOf(ProxiedRequest("GET", url, basic)), proxy.requests.toList())
          assertTrue(origin.requests.single().keys.none { it.startsWith("proxy-") })
        }
      }
    }
  }

  @Test
  fun httpProxy_https_tunnelsWithCredentialsAndUnresolvedHost() = runTest(timeout = 20.seconds) {
    // Hangs up at once: the TLS handshake fails, and the tunnel is what matters.
    ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { origin ->
      thread(isDaemon = true) {
        while (!origin.isClosed) runCatching { origin.accept().close() }
      }
      TestHttpProxy("me", "secret").use { proxy ->
        withEngine(ProxyConfig.manual("http://me:secret@127.0.0.1:${proxy.port}")) { engine ->
          assertFailsWith<KetchError.Network> {
            engine.fetch("https://origin.test:${origin.localPort}/file")
          }

          assertTrue(proxy.requests.isNotEmpty())
          val tunnel = ProxiedRequest("CONNECT", "origin.test:${origin.localPort}", basic)
          assertTrue(proxy.requests.all { it == tunnel }, proxy.requests.toString())
        }
      }
    }
  }

  @Test
  fun httpProxy_wrongCredentials_failsWith407() = runTest(timeout = 20.seconds) {
    OriginServer(content).use { origin ->
      TestHttpProxy("me", "secret").use { proxy ->
        withEngine(ProxyConfig.manual("http://me:wrong@127.0.0.1:${proxy.port}")) { engine ->
          val error = assertFailsWith<KetchError.Http> {
            engine.fetch("http://origin.test:${origin.port}/file")
          }
          assertEquals(407, error.code)
          assertTrue(origin.requests.isEmpty())
        }
      }
    }
  }

  @Test
  fun socks5Proxy_segmentedDownload_resolvesNamesAtProxy() = runTest(timeout = 30.seconds) {
    OriginServer(content).use { origin ->
      TestSocks5Proxy("me", "secret").use { proxy ->
        val folder = Files.createTempDirectory("ketch-proxy").toFile()
        val ketch = Ketch(
          httpEngine = KtorHttpEngine(),
          config = DownloadConfig(
            defaultDirectory = folder.path,
            retryCount = 0,
            proxy = ProxyConfig.manual("socks5://me:secret@127.0.0.1:${proxy.port}"),
          ),
        )
        try {
          val task = ketch.download(
            DownloadRequest(
              url = "http://origin.test:${origin.port}/file",
              destination = Destination(File(folder, "file.bin").path),
              connections = 4,
            ),
          )
          val state = task.state.first { it.isTerminal }
          val completed = assertIs<DownloadState.Completed>(state)
          assertContentEquals(content, File(completed.outputPath).readBytes())

          assertTrue(origin.requests.size >= 5, "HEAD and four segments")
          assertEquals(setOf("domain:origin.test:${origin.port}"), proxy.destinations.toSet())
        } finally {
          ketch.close()
          folder.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun socks5Proxy_wrongPassword_failsWithoutReachingOrigin() = runTest(timeout = 20.seconds) {
    OriginServer(content).use { origin ->
      TestSocks5Proxy("me", "secret").use { proxy ->
        withEngine(ProxyConfig.manual("socks5://me:wrong@127.0.0.1:${proxy.port}")) { engine ->
          assertFailsWith<KetchError.Network> {
            engine.fetch("http://origin.test:${origin.port}/file")
          }
          assertTrue(origin.requests.isEmpty())
          assertTrue(proxy.destinations.isEmpty())
        }
      }
    }
  }

  @Test
  fun requestOverride_replacesGlobalProxy() = runTest(timeout = 20.seconds) {
    OriginServer(content).use { origin ->
      TestHttpProxy().use { global ->
        TestSocks5Proxy().use { socks ->
          val folder = Files.createTempDirectory("ketch-proxy").toFile()
          val ketch = Ketch(
            httpEngine = KtorHttpEngine(),
            config = DownloadConfig(
              retryCount = 0,
              proxy = ProxyConfig.manual("http://127.0.0.1:${global.port}"),
            ),
          )
          try {
            val task = ketch.download(
              DownloadRequest(
                url = "http://origin.test:${origin.port}/file",
                destination = Destination(File(folder, "file.bin").path),
                proxy = ProxyConfig.manual("socks5://127.0.0.1:${socks.port}"),
              ),
            )
            assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })

            assertTrue(global.requests.isEmpty())
            assertTrue(socks.destinations.isNotEmpty())
          } finally {
            ketch.close()
            folder.deleteRecursively()
          }
        }
      }
    }
  }

  @Test
  fun loopbackTarget_goesDirectlyPastProxy() = runTest(timeout = 20.seconds) {
    OriginServer(content).use { origin ->
      TestHttpProxy().use { proxy ->
        withEngine(ProxyConfig.manual("http://127.0.0.1:${proxy.port}")) { engine ->
          assertContentEquals(content, engine.fetch("http://127.0.0.1:${origin.port}/file"))
          assertTrue(proxy.requests.isEmpty())
        }
      }
    }
  }

  private suspend fun withEngine(proxy: ProxyConfig, block: suspend (HttpEngine) -> Unit) {
    val engine = KtorHttpEngine()
    try {
      block(engine.withProxy(proxy))
    } finally {
      engine.close()
    }
  }

  private suspend fun HttpEngine.fetch(url: String): ByteArray {
    val received = ByteArrayOutputStream()
    download(url, null) { received.write(it) }
    return received.toByteArray()
  }
}
