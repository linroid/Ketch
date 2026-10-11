package com.linroid.ketch.hooks

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.config.HookConfig
import com.linroid.ketch.config.HookEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HookRunnerTest {
  private val launchedAt = Instant.parse("2026-10-01T12:00:00Z")
  private val clock = FixedClock(launchedAt)
  private val launcher = RecordingLauncher()
  private val ketch = FakeKetch()

  private fun TestScope.runner(vararg hooks: HookConfig): HookRunner {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val client = HttpClient(MockEngine { respondOk() })
    return HookRunner(
      hooks.toList(), CommandRunner(launcher, dispatcher), WebhookSender(client), clock,
      dispatcher,
    )
  }

  private fun events(): List<String> = launcher.launches.map { it.environment["KETCH_EVENT"]!! }

  private fun progress(bytes: Long) = DownloadProgress(bytes, 100)

  @Test
  fun attach_runsEachEventOncePerChange() = runTest {
    val runner = runner(HookConfig(events = HookEvent.entries.map { it.id }, command = listOf("x")))
    runner.attach(ketch)
    val task = FakeTask("t1", createdAt = launchedAt + 1.seconds)

    ketch.add(task)
    task.state.value = DownloadState.Downloading(progress(10))
    task.state.value = DownloadState.Downloading(progress(50))
    val done = DownloadState.Completed("/d/file.iso", 100, completedAt = launchedAt)
    task.state.value = done
    task.state.value = done.copy(sharing = true)
    ketch.remove(task)
    runCurrent()

    assertEquals(listOf("added", "started", "completed", "removed"), events())
    runner.close()
  }

  @Test
  fun attach_tasksListedBeforeAttaching_areNotAddedAndKeepTheirState() = runTest {
    val restored = FakeTask(
      "old", createdAt = launchedAt - 60.seconds,
      initial = DownloadState.Completed("/d/old.iso", 1, completedAt = launchedAt - 30.seconds),
    )
    val paused = FakeTask(
      "paused", createdAt = launchedAt - 60.seconds,
      initial = DownloadState.Paused(progress(5)),
    )
    ketch.add(restored)
    ketch.add(paused)
    val runner = runner(HookConfig(events = HookEvent.entries.map { it.id }, command = listOf("x")))
    runner.attach(ketch)
    runCurrent()
    assertEquals(emptyList(), events())

    paused.state.value = DownloadState.Downloading(progress(6))
    paused.state.value = DownloadState.Failed(KetchError.Network())
    runCurrent()

    assertEquals(listOf("started", "failed"), events())
    assertEquals("network", launcher.launches.last().environment["KETCH_ERROR_KIND"])
    runner.close()
  }

  @Test
  fun attach_completedAgainAfterGainingFiles_runsCompletedAgain() = runTest {
    val runner = runner(HookConfig(events = listOf("completed"), command = listOf("x")))
    runner.attach(ketch)
    val task = FakeTask("t", createdAt = launchedAt + 1.seconds)
    ketch.add(task)

    task.state.value = DownloadState.Completed("/d/a", 1, completedAt = launchedAt + 5.seconds)
    // Conflated: the run in between was never seen.
    task.state.value = DownloadState.Completed("/d/a", 2, completedAt = launchedAt + 9.seconds)
    runCurrent()

    assertEquals(listOf("completed", "completed"), events())
    runner.close()
  }

  @Test
  fun attach_filters_onlyRunForMatchingDownloads() = runTest {
    val runner = runner(
      HookConfig(
        events = listOf("added"),
        command = listOf("x"),
        origins = listOf("browser"),
        hosts = listOf("example.com"),
      ),
    )
    runner.attach(ketch)
    val later = launchedAt + 1.seconds
    ketch.add(FakeTask("app", later, properties = mapOf("ketch.origin" to "app")))
    val browser = mapOf("ketch.origin" to "browser")
    ketch.add(FakeTask("other", later, "https://other.org/f", browser))
    ketch.add(FakeTask("cdn", later, "https://cdn.example.com/f", browser))
    ketch.add(FakeTask("magnet", later, "magnet:?xt=urn:btih:abc", browser))
    runCurrent()

    assertEquals(listOf("cdn"), launcher.launches.map { it.environment["KETCH_TASK_ID"] })
    runner.close()
  }

  @Test
  fun attach_hooksForOtherEvents_doNotRun() = runTest {
    val runner = runner(HookConfig(events = listOf("failed"), command = listOf("x")))
    runner.attach(ketch)
    val task = FakeTask("t", createdAt = launchedAt + 1.seconds)
    ketch.add(task)
    task.state.value = DownloadState.Completed("/d/a", 1)
    runCurrent()

    assertEquals(emptyList(), events())
    runner.close()
  }

  @Test
  fun hookOf_unusableHooks_areLeftOut() {
    assertNull(Hook.of(1, HookConfig(events = listOf("completed"))))
    assertNull(Hook.of(1, HookConfig(events = listOf("nope"), command = listOf("x"))))
    assertNull(Hook.of(1, HookConfig(events = listOf("added"), webhook = "file:///etc/passwd")))
    val disabled = HookConfig(events = listOf("added"), command = listOf("x"), enabled = false)
    assertNull(Hook.of(1, disabled))
    val webhook = Hook.of(2, HookConfig(events = listOf("added"), webhook = "https://h.io/x?k=1"))
    assertEquals("hook 2 (webhook to h.io)", webhook?.label)
  }

  @Test
  fun transition_namesTheEventOfAChange() {
    val running = DownloadState.Downloading(progress(1))
    assertEquals(HookEvent.Started, transition(null, running))
    assertEquals(HookEvent.Started, transition(DownloadState.Paused(progress(1)), running))
    assertNull(transition(running, DownloadState.Downloading(progress(2))))
    assertNull(transition(null, DownloadState.Queued))
    assertEquals(HookEvent.Canceled, transition(running, DownloadState.Canceled))
    assertNull(transition(DownloadState.Canceled, DownloadState.Canceled))
    val done = DownloadState.Completed("/a", completedAt = null)
    assertEquals(HookEvent.Completed, transition(running, done))
    assertNull(transition(done, done.copy(sharing = true)))
    assertTrue(transition(running, DownloadState.Failed(KetchError.Disk())) == HookEvent.Failed)
  }
}
