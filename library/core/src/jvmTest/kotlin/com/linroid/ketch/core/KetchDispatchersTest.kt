package com.linroid.ketch.core

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.engine.FakeHttpEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    // Closing an instance leaves the shared dispatchers to the others.
    runBlocking {
      assertEquals(1, withContext(KetchDispatchers.Default.network) { 1 })
      assertEquals(1, withContext(KetchDispatchers.Default.disk) { 1 })
    }
  }

  @Test
  fun default_serialViewsOfMainRunAtTheSameTime(): Unit = runBlocking {
    // Each Ketch coordinates on main.limitedParallelism(1): one instance's view must never wait
    // for another's, as it would if main were itself a serial view shared by the process.
    val first = KetchDispatchers.Default.main.limitedParallelism(1)
    val second = KetchDispatchers.Default.main.limitedParallelism(1)
    val bothRunning = CountDownLatch(2)
    withTimeout(5.seconds) {
      listOf(first, second).map { view ->
        async(view) {
          bothRunning.countDown()
          check(bothRunning.await(3, TimeUnit.SECONDS)) { "The views ran one after the other" }
        }
      }.awaitAll()
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
    val disk = threads.filter { it.name.startsWith("ketch-disk-") }
    assertTrue(network.isNotEmpty() && disk.isNotEmpty())
    assertTrue((network + disk).all { it.isDaemon })
    assertTrue(network.size <= DEFAULT_NETWORK_THREADS)
    assertTrue(disk.size <= DEFAULT_DISK_THREADS)
  }

  @Test
  fun threadPool_startsThreadsOnlyForWorkRunningAtOnce() = runBlocking {
    val pool = ketchThreadPool("ketch-demand", threads = 16)
    repeat(500) { withContext(pool) {} }
    val started = Thread.getAllStackTraces().keys.count { it.name.startsWith("ketch-demand-") }
    assertTrue(started <= 4, "Started $started threads for work that ran one task at a time")
  }

  @Test
  fun threadPool_threadEndsOnceIdle() = runBlocking {
    val pool = ketchThreadPool("ketch-test", threads = 2, keepAlive = 50.milliseconds)
    val thread = withContext(pool) { Thread.currentThread() }
    // Coroutine debug mode, on with assertions, names the running coroutine after the thread's
    // name until the thread hands it back, which it may not have done yet.
    assertEquals("ketch-test-1", thread.name.substringBefore(" @"))
    assertTrue(thread.isDaemon)
    withTimeout(5.seconds) {
      while (thread.isAlive) delay(10)
    }
  }

  @Test
  fun threadPool_runsAtMostItsThreadsAtOnce(): Unit = runBlocking {
    val pool = ketchThreadPool("ketch-cap", threads = 3)
    val running = AtomicInteger()
    val most = AtomicInteger()
    List(12) {
      async(pool) {
        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        Thread.sleep(50)
        running.decrementAndGet()
      }
    }.awaitAll()
    assertEquals(3, most.get())
  }

  @Test
  fun close_onDefault_leavesItRunning() = runBlocking {
    KetchDispatchers.Default.close()
    assertEquals(1, withContext(KetchDispatchers.Default.network) { 1 })
    assertEquals(1, withContext(KetchDispatchers.Default.disk) { 1 })
  }
}
