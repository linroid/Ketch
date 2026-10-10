package com.linroid.ketch.app.desktop

import com.linroid.ketch.config.RemoteConfig
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadsLockTest {
  private val dir = Files.createTempDirectory("ketch-downloads-lock").toFile()
  private val info = File(dir, DownloadsLock.INFO_FILE)

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  /** Holds the lock as `ketch server` does, until closed. */
  private fun holdLikeTheCommandLine(): FileChannel = FileChannel.open(
    File(dir, DownloadsLock.LOCK_FILE).toPath(),
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
  ).also { it.lock() }

  private fun claim(isAlive: (Long) -> Boolean = { true }) = DownloadsLock.claim(
    dir,
    isAlive,
    describeTimeout = 300.milliseconds,
    retryInterval = 10.milliseconds,
  )

  @Test
  fun claim_whileNoOneRunsTheDownloads_holdsTheLockUntilClosed() {
    val held = assertIs<DownloadsClaim.Held>(claim())

    // Within one JVM a second lock fails as it would for another process.
    assertEquals(DownloadsClaim.Taken(null), claim())
    held.close()
    assertIs<DownloadsClaim.Held>(claim()).close()
  }

  @Test
  fun claim_whileNoOneRunsTheDownloads_removesTheInfoFileOfACommandThatStopped() {
    info.writeText("""{"command":"ketch server","pid":7,"url":"http://127.0.0.1:8642"}""")

    assertIs<DownloadsClaim.Held>(claim()).close()

    // The `ketch` commands would otherwise send their requests to it.
    assertFalse(info.exists())
  }

  @Test
  fun claim_whileACommandRunsTheDownloads_returnsIt() {
    holdLikeTheCommandLine().use {
      info.writeText(
        """{"command":"ketch server","pid":42,"url":"http://127.0.0.1:8642","token":"secret"}"""
      )

      val claim = claim(isAlive = { it == 42L })

      assertEquals(
        DownloadsClaim.Taken(CliEngine("ketch server", 42, "http://127.0.0.1:8642", "secret")),
        claim,
      )
    }
  }

  @Test
  fun claim_whileTheCommandIsStarting_waitsForItToSayWhereItListens() {
    holdLikeTheCommandLine().use {
      info.writeText("""{"command":"ketch server","pid":42}""")
      val describe = thread {
        Thread.sleep(50)
        info.writeText("""{"command":"ketch server","pid":42,"url":"http://127.0.0.1:8642"}""")
      }

      val claim = DownloadsLock.claim(dir, { true }, describeTimeout = 5.seconds)

      describe.join()
      val engine = assertIs<DownloadsClaim.Taken>(claim).engine
      assertEquals("http://127.0.0.1:8642", engine?.url)
    }
  }

  @Test
  fun claim_withTheInfoOfAStoppedProcess_returnsAnUnknownHolder() {
    holdLikeTheCommandLine().use {
      // Left by a command that stopped, while a second app copy or a new command holds the lock.
      info.writeText("""{"command":"ketch server","pid":42,"url":"http://127.0.0.1:8642"}""")

      assertEquals(DownloadsClaim.Taken(null), claim(isAlive = { false }))
    }
  }

  @Test
  fun read_fileItCannotUnderstand_returnsNull() {
    assertNull(DownloadsLock.read(dir))
    info.writeText("not json")
    assertNull(DownloadsLock.read(dir))
    info.writeText("""{"command":"ketch server"}""")
    assertNull(DownloadsLock.read(dir))
  }

  @Test
  fun device_loopbackAddress_namesTheDeviceAfterTheCommand() {
    val engine = CliEngine("ketch mcp --standalone", 42, "http://127.0.0.1:50123", "secret")

    assertEquals(
      RemoteConfig(
        host = "127.0.0.1",
        port = 50123,
        apiToken = "secret",
        name = "ketch mcp --standalone",
      ),
      engine.device(),
    )
    assertEquals("localhost", engine.copy(url = "http://localhost:8642").device()?.host)
    assertEquals("[::1]", engine.copy(url = "http://[::1]:8642").device()?.host)
  }

  @Test
  fun device_addressElsewhere_isNotSentTheToken() {
    val engine = CliEngine("ketch server", 42, token = "secret")

    for (url in listOf(
      null,
      "http://nas.local:8642",
      "http://93.184.216.34:8642",
      "https://127.0.0.1:8642",
      "http://127.0.0.1",
      "not a url",
    )) {
      assertNull(engine.copy(url = url).device(), "$url")
    }
  }
}
