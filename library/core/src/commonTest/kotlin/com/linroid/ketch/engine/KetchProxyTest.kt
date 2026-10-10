package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KetchProxyTest {
  private val http = ProxyConfig.manual("http://proxy.test:3128")
  private val socks = ProxyConfig.manual("socks5://socks.test:1080")

  @Test
  fun download_globalProxy_carriesEveryRequest() = withKetch(DownloadConfig(proxy = http)) {
    val task = ketch.download(request())
    idle()

    assertIs<DownloadState.Completed>(task.state.value)
    assertTrue(proxies.isNotEmpty())
    assertEquals(setOf(http), proxies.toSet())
  }

  @Test
  fun download_requestProxy_replacesGlobalProxy() = withKetch(DownloadConfig(proxy = http)) {
    val task = ketch.download(request().copy(proxy = socks))
    idle()

    assertIs<DownloadState.Completed>(task.state.value)
    assertEquals(setOf(socks), proxies.toSet())
  }

  @Test
  fun download_afterUpdateConfig_usesNewProxy() = withKetch(DownloadConfig()) {
    ketch.updateConfig(DownloadConfig(retryCount = 0, proxy = ProxyConfig.Direct))
    val task = ketch.download(request())
    idle()

    assertIs<DownloadState.Completed>(task.state.value)
    assertEquals(setOf(ProxyConfig.Direct), proxies.toSet())
  }

  @Test
  fun resolve_usesGlobalProxy() = withKetch(DownloadConfig(proxy = socks)) {
    ketch.resolve("https://example.com/file.bin")

    assertEquals(listOf(socks), proxies)
  }

  @Test
  fun download_engineWithoutProxySupport_failsWithoutConnecting() = withKetch(
    DownloadConfig(proxy = http),
    fake = FakeHttpEngine(),
  ) {
    val task = ketch.download(request())
    idle()

    val failed = assertIs<DownloadState.Failed>(task.state.value)
    assertIs<KetchError.Unsupported>(failed.error)
    val fake = engine as FakeHttpEngine
    assertEquals(0, fake.headCallCount + fake.probeCallCount + fake.downloadCallCount)
  }

  private fun withKetch(
    config: DownloadConfig,
    fake: HttpEngine = ProxyRecordingEngine(),
    block: suspend Scenario.() -> Unit,
  ) = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-proxy-test"
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = fake,
      taskStore = InMemoryTaskStore(),
      config = config.copy(retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      Scenario(this, ketch, fake, folder).block()
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private class Scenario(
    private val scope: TestScope,
    val ketch: Ketch,
    val engine: HttpEngine,
    private val folder: Path,
  ) {
    /** The proxies of the requests made so far. */
    val proxies: List<ProxyConfig> get() = (engine as ProxyRecordingEngine).proxies

    fun idle() = scope.advanceUntilIdle()

    fun request() = DownloadRequest(
      url = "https://example.com/file.bin",
      destination = Destination((folder / "file.bin").toString()),
    )
  }

  /** Serves [fake] and records the proxy each request went through. */
  private class ProxyRecordingEngine(
    private val fake: FakeHttpEngine = FakeHttpEngine(),
  ) : HttpEngine by fake {
    val proxies = mutableListOf<ProxyConfig>()

    override fun withProxy(proxy: ProxyConfig): HttpEngine = object : HttpEngine by fake {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
        proxies += proxy
        return fake.head(url, headers)
      }

      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        proxies += proxy
        fake.download(url, range, headers, onData)
      }
    }
  }
}
