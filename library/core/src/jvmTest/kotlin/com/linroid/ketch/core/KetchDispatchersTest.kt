package com.linroid.ketch.core

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.engine.FakeHttpEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KetchDispatchersTest {
  private val folder: File = Files.createTempDirectory("ketch-dispatchers").toFile()

  @AfterTest
  fun cleanUp() {
    folder.deleteRecursively()
  }

  @Test
  fun default_isSharedByEveryKetch() {
    val first = Ketch(httpEngine = FakeHttpEngine())
    val second = Ketch(httpEngine = FakeHttpEngine())
    try {
      assertSame(KetchDispatchers.Default, first.dispatchers)
      assertSame(first.dispatchers, second.dispatchers)
    } finally {
      first.close()
      second.close()
    }
  }

  @Test
  fun default_downloadsOnBoundedDaemonThreadsWithoutAMainThread() = runBlocking {
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      config = DownloadConfig(defaultDirectory = folder.path),
    )
    try {
      ketch.start()
      val task = ketch.download(DownloadRequest(url = "https://example.com/file.bin"))
      val state = withTimeout(10.seconds) {
        task.state.first { it is DownloadState.Completed || it is DownloadState.Failed }
      }
      assertIs<DownloadState.Completed>(state)
    } finally {
      ketch.close()
    }

    val threads = Thread.getAllStackTraces().keys
    assertTrue(threads.none { it.name.startsWith("ketch-main") })
    val network = threads.filter { it.name.startsWith("ketch-network-") }
    val io = threads.filter { it.name.startsWith("ketch-io-") }
    assertTrue(network.isNotEmpty() && io.isNotEmpty())
    assertTrue((network + io).all { it.isDaemon })
    assertTrue(network.size <= DEFAULT_NETWORK_THREADS)
    assertTrue(io.size <= DEFAULT_IO_THREADS)
  }

  @Test
  fun threadPool_threadEndsOnceIdle() = runBlocking {
    val pool = ketchThreadPool("ketch-test", threads = 2, keepAlive = 50.milliseconds)
    try {
      val thread = withContext(pool) { Thread.currentThread() }
      assertEquals("ketch-test-1", thread.name)
      assertTrue(thread.isDaemon)
      withTimeout(5.seconds) {
        while (thread.isAlive) delay(10)
      }
    } finally {
      pool.close()
    }
  }

  @Test
  fun close_leavesCallerDispatchersOpen() = runBlocking {
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      ketch.close()
      assertEquals(1, withContext(dispatcher) { 1 })
      assertFalse(executor.isShutdown)
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun close_onDefault_leavesItRunning() = runBlocking {
    KetchDispatchers.Default.close()
    assertEquals(1, withContext(KetchDispatchers.Default.network) { 1 })
    assertEquals(1, withContext(KetchDispatchers.Default.io) { 1 })
  }
}
