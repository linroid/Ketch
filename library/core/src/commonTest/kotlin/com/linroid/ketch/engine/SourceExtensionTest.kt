package com.linroid.ketch.engine

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceExtension
import com.linroid.ketch.core.engine.SourceHost
import com.linroid.ketch.core.task.ControlCommand
import com.linroid.ketch.core.task.ControlLedgerException
import com.linroid.ketch.core.task.ControlRevision
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The seam through which a source adds controllers and lifecycle hooks to Ketch: controller
 * lookup by key, the hooks, and the host's guarded, ledgered control changes.
 */
class SourceExtensionTest {
  private val folder = "/tmp/ketch-source-extension/"

  /** A controller the extension contributes. */
  private class Greeter {
    companion object Key : ControlKey<Greeter> {
      override val name: String = "greeter"
    }
  }

  private object OtherKey : ControlKey<String> {
    override val name: String = "other"
  }

  /** [SelectableSource] with an extension that records its hooks. */
  private class ExtendedSource(
    val inner: SelectableSource = SelectableSource(),
  ) : DownloadSource<Unit> by inner {
    val greeter = Greeter()
    var host: SourceHost? = null
    var attaches = 0
    var starts = 0
    val completed = mutableListOf<String>()
    val selections = mutableListOf<Set<String>>()

    override fun attach(host: SourceHost): SourceExtension {
      attaches++
      this.host = host
      return object : SourceExtension {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> controller(key: ControlKey<T>): T? =
          if (key === Greeter.Key) greeter as T else null

        override suspend fun start() {
          starts++
        }

        override suspend fun taskCompleted(task: DownloadTask) {
          completed += task.taskId
        }

        override suspend fun selectionChanged(task: DownloadTask, fileIds: Set<String>) {
          selections += fileIds
        }
      }
    }
  }

  private fun TestScope.ketch(source: DownloadSource<*>): Ketch {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = RecordingTaskStore(),
      config = DownloadConfig(retryCount = 0, saveIntervalMs = 60_000),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
  }

  private suspend fun TestScope.completedTask(
    ketch: Ketch,
    source: ExtendedSource,
  ): DownloadTask {
    val request = DownloadRequest(
      url = "pick:files",
      destination = Destination(folder),
      selectedFileIds = setOf("0", "1"),
    )
    val task = ketch.download(request)
    runCurrent()
    source.inner.runs.value.last().finish.complete(Unit)
    runCurrent()
    assertIs<DownloadState.Completed>(task.state.value)
    return task
  }

  private fun command(
    key: String,
    digest: Char = 'a',
    afterSave: suspend () -> Unit = {},
  ) = ControlCommand(
    key = key,
    digest = digest.toString().repeat(64),
    precondition = {},
    reserve = { generation, _ -> ControlRevision("epoch", generation + 10) },
    afterSave = afterSave,
  )

  @Test
  fun control_returnsTheExtensionsControllerForItsKeyOnly() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      assertSame(source.greeter, ketch.controller(Greeter))
      assertNull(ketch.controller(OtherKey))
      assertEquals(1, source.attaches)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun control_sourceWithoutExtension_isNull() = runTest {
    val ketch = ketch(SelectableSource())
    try {
      assertNull(ketch.controller(Greeter))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun start_runsTheExtensionOnce() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      ketch.start()
      ketch.start()
      assertEquals(1, source.starts)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun taskCompleted_reachesTheExtensionOfTheTasksSource() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      assertEquals(listOf(task.taskId), source.completed)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun selectFiles_smallerSelectionOfCompletedTask_tellsTheExtension() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      task.selectFiles(setOf("0"))
      runCurrent()
      assertEquals(listOf(setOf("0")), source.selections)
      assertIs<DownloadState.Completed>(task.state.value)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSharing_savesIntentAndReplaysAnExactRetry() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      val host = assertNotNull(source.host)

      val first = host.setSharing(task, true, command("k"))
      val retry = host.setSharing(task, true, command("k"))

      assertTrue(first.sharing)
      assertFalse(first.replayed)
      assertTrue(retry.replayed)
      assertEquals(first.revision, retry.revision)
      assertEquals(true, host.record(task)?.control?.sharing)
      assertEquals(1, host.record(task)?.control?.commands?.size)
      val reused = assertFailsWith<ControlLedgerException> {
        host.setSharing(task, false, command("k", digest = 'b'))
      }
      assertEquals(ControlLedgerException.Reason.KEY_REUSED, reused.reason)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSharing_afterSave_runsOnceTheIntentIsSaved() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      val host = assertNotNull(source.host)
      val seen = mutableListOf<Boolean?>()
      val share = command("k", afterSave = { seen += host.record(task)?.control?.sharing })

      host.setSharing(task, true, share)
      host.setSharing(task, true, share)

      // An exact retry answers from the ledger without running it again.
      assertEquals(listOf<Boolean?>(true), seen)
      val failed = assertFailsWith<IllegalStateException> {
        host.setSharing(task, false, command("j", afterSave = { error("failed") }))
      }
      assertEquals("failed", failed.message)
      // The change stays saved.
      assertEquals(false, host.record(task)?.control?.sharing)
      assertEquals(2, host.record(task)?.control?.commands?.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun setSharing_unfinishedTask_isRefused() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = ketch.download(DownloadRequest("pick:files", destination = Destination(folder)))
      runCurrent()
      val host = assertNotNull(source.host)
      assertFailsWith<IllegalStateException> { host.setSharing(task, true, command("k")) }
      assertNull(host.record(task)?.control)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun showSharing_publishesOnCompletedTasksOnly() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      val host = assertNotNull(source.host)

      host.showSharing(task, true)
      assertTrue(assertIs<DownloadState.Completed>(task.state.value).sharing)
      host.showSharing(task, false)
      assertFalse(assertIs<DownloadState.Completed>(task.state.value).sharing)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun saveSharingIntent_keepsTheIntentWithoutACommand() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      val host = assertNotNull(source.host)

      host.saveSharingIntent(task, true)

      assertEquals(true, host.record(task)?.control?.sharing)
      assertEquals(emptyList(), host.record(task)?.control?.commands)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun saveSharingIntent_onlyIfFalse_savesNothing() = runTest {
    val source = ExtendedSource()
    val ketch = ketch(source)
    try {
      val task = completedTask(ketch, source)
      val host = assertNotNull(source.host)

      host.saveSharingIntent(task, true) { false }

      assertNull(host.record(task)?.control)
    } finally {
      ketch.close()
    }
  }
}
