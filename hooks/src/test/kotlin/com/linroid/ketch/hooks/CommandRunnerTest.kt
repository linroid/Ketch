package com.linroid.ketch.hooks

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.config.HookEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CommandRunnerTest {
  private val payload = HookPayload.of(
    event = HookEvent.Completed,
    at = Instant.parse("2026-10-01T12:00:00Z"),
    deliveryId = "d1",
    taskId = "t1",
    request = DownloadRequest(url = "https://example.com/a.zip"),
    state = DownloadState.Completed("/d/a.zip; rm -rf ~", 3),
    outputPath = null,
    createdAt = null,
    config = null,
  )

  @Test
  fun run_passesThePayloadOnStdinAndInTheEnvironment_neverInTheArguments() = runTest {
    val launcher = RecordingLauncher()
    val runner = CommandRunner(launcher, StandardTestDispatcher(testScheduler))

    val outcome = runner.run(listOf("/bin/notify", "--done"), "/tmp", 5.seconds, payload)

    assertEquals(HookOutcome.Done, outcome)
    val launch = launcher.launches.single()
    assertEquals(listOf("/bin/notify", "--done"), launch.command)
    assertEquals(File("/tmp"), launch.workingDirectory)
    assertEquals("/d/a.zip; rm -rf ~", launch.environment["KETCH_OUTPUT_PATH"])
    assertEquals(payload.toJson(), launch.stdin)
    assertEquals(5.seconds, launch.process.waitedFor)
  }

  @Test
  fun run_commandOutlivingItsTimeout_isKilled() = runTest {
    val launcher = RecordingLauncher(hangs = true)
    val runner = CommandRunner(launcher, StandardTestDispatcher(testScheduler))

    val outcome = runner.run(listOf("sleep", "100"), null, 1.seconds, payload)

    assertIs<HookOutcome.Failed>(outcome)
    assertTrue(launcher.launches.single().process.killed)
  }

  @Test
  fun run_failingCommand_reportsItsStatus() = runTest {
    val launcher = RecordingLauncher(exitCode = 3)
    val runner = CommandRunner(launcher, StandardTestDispatcher(testScheduler))

    val outcome = runner.run(listOf("x"), null, 1.seconds, payload)

    assertEquals(HookOutcome.Failed("exited with status 3"), outcome)
  }

  @Test
  fun run_programThatCannotStart_fails() = runTest {
    val launcher = ProcessLauncher { _, _, _ -> throw IOException("no such file /secret/arg") }
    val runner = CommandRunner(launcher, StandardTestDispatcher(testScheduler))

    val outcome = runner.run(listOf("x"), null, 1.seconds, payload)

    assertIs<HookOutcome.Failed>(outcome)
    assertFalse("secret" in outcome.reason)
  }

  @Test
  fun systemLauncher_realProcess_readsStdinAndEnvironment() = runTest {
    if (System.getProperty("os.name").startsWith("Windows")) return@runTest
    val dir = Files.createTempDirectory("hooks").toFile()
    try {
      val out = File(dir, "out")
      val runner = CommandRunner(ProcessLauncher.System)
      val script = "cat > \"\$1\"; printf '%s' \"\$KETCH_EVENT\" > \"\$1.event\""
      val command = listOf("sh", "-c", script, "sh", out.path)

      val outcome = runner.run(command, null, 10.seconds, payload)

      assertEquals(HookOutcome.Done, outcome)
      assertEquals(payload.toJson(), out.readText())
      assertEquals("completed", File(dir, "out.event").readText())
    } finally {
      dir.deleteRecursively()
    }
  }
}
