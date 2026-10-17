package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * [DownloadContext.globalThrottle] is what a source that keeps transferring after its run, such as
 * a seeding torrent, pays: Ketch's global limit, without the task limit of a run that ended.
 */
class GlobalThrottleTest {
  private class CountingLimiter : SpeedLimiter {
    var paid = 0L
    override suspend fun acquire(bytes: Int) {
      paid += bytes
    }
  }

  /** Completes at once, handing over the context its run had. */
  private class CapturingSource : DownloadSource<Unit> {
    val context = CompletableDeferred<DownloadContext>()
    override val stateSerializer = Unit.serializer()
    override val type = FIXTURE
    override val managesOwnFileIo = true
    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      ResolvedSource(
        url = (input as ResolveInput.Url).url, sourceType = type, totalBytes = 4,
        supportsResume = true, suggestedFileName = "fixture", maxSegments = 1,
      )
    override suspend fun download(context: DownloadContext, state: Unit?) {
      this.context.complete(context)
    }
  }

  @Test
  fun globalThrottle_afterTheRun_paysTheGlobalLimitWithoutTheTasks() = runTest {
    // A task limit of 1 byte per second would hold a megabyte for days.
    val request = DownloadRequest("fixture:input", destination = Destination(OUTPUT),
      speedLimit = SpeedLimit.of(1))
    val now = Clock.System.now()
    val record = TaskRecord(
      taskId = "task", request = request, outputPath = OUTPUT, state = TaskState.QUEUED,
      totalBytes = 4, sourceType = FIXTURE, sourceState = SourceState(FIXTURE, "{}"),
      createdAt = now, updatedAt = now,
    )
    val handle = object : TaskHandle {
      override val taskId = record.taskId
      override val request = record.request
      override val createdAt = record.createdAt
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(record) {}
    }
    val global = CountingLimiter()
    val source = CapturingSource()
    val dispatcher = StandardTestDispatcher(testScheduler)
    DownloadExecution(
      handle = handle,
      sourceResolver = SourceResolver(listOf(source)),
      fileNameResolver = DefaultFileNameResolver(),
      config = DownloadConfig(saveIntervalMs = 60_000),
      globalLimiter = global,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    ).execute()
    assertIs<DownloadState.Completed>(handle.mutableState.value)
    val context = source.context.await()

    // The run's own throttle still waits for its task limit.
    assertNull(withTimeoutOrNull(10.seconds) { context.throttle(1_000_000) })
    assertEquals(0, global.paid)
    withTimeout(10.seconds) { context.globalThrottle(1_000_000) }
    assertEquals(1_000_000, global.paid)
  }

  private companion object {
    const val FIXTURE = "fixture"
    const val OUTPUT = "/tmp/ketch-global-throttle-output"
  }
}
