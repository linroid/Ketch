package com.linroid.ketch.app.desktop

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Each system lists what keeps it awake, so these check that the native calls reach it: on macOS
 * `pmset`, on Linux logind, where it runs. Windows lists execution states only to administrators.
 */
class SystemSleepInhibitorsTest {
  private val reason = "Ketch test ${UUID.randomUUID()}"

  @Test
  fun macSleepAssertion_acquireThenRelease_listedOnlyWhileHeld() {
    if (DesktopOs.current != DesktopOs.MAC) return
    val inhibitor = MacSleepAssertion(reason)
    try {
      inhibitor.acquire()
      assertTrue(reason in run("pmset", "-g", "assertions").orEmpty())
    } finally {
      inhibitor.release()
    }
    assertFalse(reason in run("pmset", "-g", "assertions").orEmpty())
  }

  @Test
  fun logindIdleInhibitor_acquireThenRelease_listedOnlyWhileHeld() {
    if (DesktopOs.current != DesktopOs.LINUX || logindLocks() == null) return
    val inhibitor = LogindIdleInhibitor(reason)
    try {
      inhibitor.acquire()
      assertTrue(eventually { reason in logindLocks().orEmpty() })
    } finally {
      inhibitor.release()
    }
    assertTrue(eventually { reason !in logindLocks().orEmpty() })
  }

  @Test
  fun logindIdleInhibitor_lockEndsWhileHeld_takesItAgainUpToTheLimit() {
    if (DesktopOs.current == DesktopOs.WINDOWS) return
    withStartLog { starts, inhibitor ->
      inhibitor.acquire()
      // The first start and each of the 3 retries.
      assertTrue(eventually { starts() == 4 })
      Thread.sleep(SETTLE_MS)
      assertEquals(4, starts())
      inhibitor.release()
    }
  }

  @Test
  fun logindIdleInhibitor_released_stopsRetrying() {
    if (DesktopOs.current == DesktopOs.WINDOWS) return
    withStartLog { starts, inhibitor ->
      inhibitor.acquire()
      assertTrue(eventually { starts() >= 2 })
      inhibitor.release()
      Thread.sleep(SETTLE_MS)
      val released = starts()
      Thread.sleep(SETTLE_MS)
      assertEquals(released, starts())
    }
  }

  // An inhibitor whose lock ends as soon as it starts, and how often it started.
  private fun withStartLog(block: (starts: () -> Int, LogindIdleInhibitor) -> Unit) {
    val log = File.createTempFile("ketch-inhibit", ".log")
    try {
      val inhibitor = LogindIdleInhibitor(
        reason = reason,
        command = listOf("sh", "-c", "echo started >> '${log.path}'"),
        retryDelay = 20.milliseconds,
        maxRetries = 3,
      )
      block({ log.readLines().size }, inhibitor)
    } finally {
      log.delete()
    }
  }

  // The locks logind holds, or null without logind.
  private fun logindLocks(): String? = run("systemd-inhibit", "--list", "--no-pager")

  // systemd-inhibit takes and drops its lock on its own time.
  private fun eventually(check: () -> Boolean): Boolean {
    repeat(50) {
      if (check()) return true
      Thread.sleep(100)
    }
    return false
  }

  private fun run(vararg command: String): String? = try {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    // Wide enough that systemd-inhibit never shortens a reason.
    builder.environment()["COLUMNS"] = "1000"
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exited = process.waitFor(10, TimeUnit.SECONDS)
    output.takeIf { exited && process.exitValue() == 0 }
  } catch (e: IOException) {
    null
  }

  private companion object {
    // Longer than every retry together: 20 + 40 + 80 ms.
    const val SETTLE_MS = 500L
  }
}
