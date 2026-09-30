package com.linroid.ketch.app.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleInstanceTest {
  private val dir = Files.createTempDirectory("ketch-single-instance").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun acquire_whileRunning_forwardsArgumentsAndReturnsNull() {
    val received = LinkedBlockingQueue<List<String>>()
    val running = assertNotNull(SingleInstance.acquire(dir, emptyList()) { received.put(it) })
    running.use {
      val files = listOf("/downloads/a b.torrent", "/downloads/ü.torrent")
      assertNull(SingleInstance.acquire(dir, files) { error("Second instance must not run") })
      assertEquals(files, received.poll(5, TimeUnit.SECONDS))

      // Relaunching without files still reaches the running app, which raises its window.
      assertNull(SingleInstance.acquire(dir, emptyList()) { })
      assertEquals(emptyList(), received.poll(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun acquire_withWrongToken_ignoresArguments() {
    val received = LinkedBlockingQueue<List<String>>()
    SingleInstance.acquire(dir, emptyList()) { received.put(it) }!!.use {
      val endpoint = File(dir, "app.endpoint")
      val port = endpoint.readLines().first()
      endpoint.writeText("$port\nforged")

      SingleInstance.acquire(dir, listOf("/tmp/a.torrent")) { }
      assertNull(received.poll(500, TimeUnit.MILLISECONDS))
    }
  }

  @Test
  fun request_whileRunning_returnsTheReply() {
    val received = LinkedBlockingQueue<List<String>>()
    SingleInstance.acquire(
      dir,
      emptyList(),
      onRequest = { request -> "reply to $request" },
    ) { received.put(it) }!!.use {
      assertEquals("reply to connect", SingleInstance.request(dir, "connect"))
      // Requests are not launches: the app gets no arguments and doesn't raise its window.
      assertNull(received.poll(200, TimeUnit.MILLISECONDS))
    }
  }

  @Test
  fun request_answeredSlowly_doesNotHoldUpForwardedFiles() {
    val received = LinkedBlockingQueue<List<String>>()
    val release = CountDownLatch(1)
    SingleInstance.acquire(
      dir,
      emptyList(),
      onRequest = {
        release.await(5, TimeUnit.SECONDS)
        "late"
      },
    ) { received.put(it) }!!.use {
      val reply = Executors.newSingleThreadExecutor().submit<String?> {
        SingleInstance.request(dir, "connect")
      }
      assertNull(SingleInstance.acquire(dir, listOf("/downloads/a.torrent")) { })
      assertEquals(listOf("/downloads/a.torrent"), received.poll(5, TimeUnit.SECONDS))
      release.countDown()
      assertEquals("late", reply.get(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun request_withoutAnswer_returnsNull() {
    SingleInstance.acquire(dir, emptyList()) { }!!.use {
      assertNull(SingleInstance.request(dir, "unknown"))
    }
  }

  @Test
  fun request_withoutRunningInstance_returnsNull() {
    assertNull(SingleInstance.request(dir, "connect"))
    // An app that exited leaves its endpoint file behind.
    SingleInstance.acquire(dir, emptyList(), onRequest = { "reply" }) { }!!.close()
    assertNull(SingleInstance.request(dir, "connect"))
  }

  @Test
  fun fileArguments_acceptsPathsAndFileUris() {
    val files = fileArguments(listOf("-psn_0_12345", "", "file:///tmp/a%20b.torrent", "c.torrent"))
    assertEquals(listOf(File("/tmp/a b.torrent"), File("c.torrent").absoluteFile), files)
  }
}
