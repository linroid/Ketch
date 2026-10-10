package com.linroid.ketch.app.desktop

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
