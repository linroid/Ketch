package com.linroid.ketch.app.desktop

import java.io.File
import java.nio.file.Files
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
  fun fileArguments_acceptsPathsAndFileUris() {
    val files = fileArguments(listOf("-psn_0_12345", "", "file:///tmp/a%20b.torrent", "c.torrent"))
    assertEquals(listOf(File("/tmp/a b.torrent"), File("c.torrent").absoluteFile), files)
  }
}
