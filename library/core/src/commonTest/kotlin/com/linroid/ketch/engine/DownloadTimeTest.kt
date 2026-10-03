package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class DownloadTimeTest {
  private val timeSource = TestTimeSource()
  private val now = Instant.fromEpochMilliseconds(0)
  private val request = DownloadRequest("fixture:input", destination = Destination(OUTPUT))

  // Sub-millisecond, as Clock.System is on the JVM and iOS.
  private val clock = object : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
  }

  @Test
  fun execute_completed_reportsSizeAndTimeSpent() = runTest {
    val handle = handle(record(TaskState.QUEUED, downloadTime = Duration.ZERO))

    execution(handle, FixtureSource(runTime = 3.seconds)).execute()

    assertEquals(
      DownloadState.Completed(OUTPUT, 4, 3.seconds, FINISHED_AT),
      handle.mutableState.value,
    )
    assertEquals(3.seconds, handle.record.value.downloadTime)
  }

  @Test
  fun execute_resumed_addsToTimeOfEarlierRuns() = runTest {
    val handle = handle(record(TaskState.PAUSED, downloadTime = 5.seconds))

    execution(handle, FixtureSource(runTime = 2.seconds))
      .execute(DownloadExecution.ResumeInfo(handle.record.value, emptyList()))

    assertEquals(
      DownloadState.Completed(OUTPUT, 4, 7.seconds, FINISHED_AT),
      handle.mutableState.value,
    )
    assertEquals(7.seconds, handle.record.value.downloadTime)
  }

  @Test
  fun execute_canceled_savesTimeSpentSoFar() = runTest {
    val started = CompletableDeferred<Unit>()
    val handle = handle(record(TaskState.QUEUED, downloadTime = Duration.ZERO))
    val source = FixtureSource(runTime = 2.seconds, started = started, finishes = false)

    val job = launch { execution(handle, source).execute() }
    started.await()
    job.cancelAndJoin()

    assertEquals(2.seconds, handle.record.value.downloadTime)
  }

  @Test
  fun execute_recordWithoutDownloadTime_keepsItUnknown() = runTest {
    val handle = handle(record(TaskState.PAUSED, downloadTime = null))

    execution(handle, FixtureSource(runTime = 2.seconds))
      .execute(DownloadExecution.ResumeInfo(handle.record.value, emptyList()))

    assertEquals(DownloadState.Completed(OUTPUT, 4, null, FINISHED_AT), handle.mutableState.value)
    assertEquals(null, handle.record.value.downloadTime)
  }

  @Test
  fun start_completedRecord_restoresSizeAndTimeSpent() = runTest {
    val store = InMemoryTaskStore()
    store.save(
      record(TaskState.COMPLETED, downloadTime = 5.seconds).copy(completedAt = FINISHED_AT)
    )
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      ketch.start()
      runCurrent()

      val state = ketch.tasks.value.single().state.value
      assertEquals(DownloadState.Completed(OUTPUT, 4, 5.seconds, FINISHED_AT), state)
    } finally {
      ketch.close()
      runCurrent()
    }
  }

  @Test
  fun completedAt_isTruncatedToMilliseconds() = runTest {
    val handle = handle(record(TaskState.QUEUED, downloadTime = Duration.ZERO))

    execution(handle, FixtureSource(runTime = 1.seconds)).execute()

    // Task stores keep milliseconds; a finer value would change after a restart.
    val completed = handle.mutableState.value as DownloadState.Completed
    assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_123), completed.completedAt)
    assertEquals(completed.completedAt, handle.record.value.completedAt)
  }

  @Test
  fun execute_zeroByteFile_stampsCompletedAt() = runTest {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-zero-byte-test"
    platformFileSystem.createDirectories(folder)
    try {
      val output = (folder / "empty.bin").toString()
      val handle = handle(
        record(TaskState.QUEUED, downloadTime = Duration.ZERO)
          .copy(request = request.copy(destination = Destination(output)))
      )

      execution(handle, FixtureSource(runTime = 1.seconds, totalBytes = 0, selfManaged = false))
        .execute()

      val completed = handle.mutableState.value as DownloadState.Completed
      assertEquals(0L, completed.totalBytes)
      assertEquals(FINISHED_AT, completed.completedAt)
      assertEquals(FINISHED_AT, handle.record.value.completedAt)
    } finally {
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun record(state: TaskState, downloadTime: Duration?) = TaskRecord(
    taskId = "task",
    request = request,
    outputPath = OUTPUT,
    state = state,
    totalBytes = 4,
    sourceType = FIXTURE,
    sourceResumeState = SourceResumeState(FIXTURE, ""),
    downloadTime = downloadTime,
    createdAt = now,
    updatedAt = now,
  )

  private fun handle(record: TaskRecord) = object : TaskHandle {
    override val taskId = record.taskId
    override val request = record.request
    override val createdAt = record.createdAt
    override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
    override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
    override val mutableQueuePosition = MutableStateFlow<Int?>(null)
    override val record = AtomicSaver(record) {}
  }

  private fun TestScope.execution(handle: TaskHandle, source: DownloadSource): DownloadExecution {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return DownloadExecution(
      handle = handle,
      sourceResolver = SourceResolver(listOf(source)),
      fileNameResolver = DefaultFileNameResolver(),
      config = DownloadConfig(saveIntervalMs = 60_000),
      globalLimiter = SpeedLimiter.Unlimited,
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      timeSource = timeSource,
      clock = clock,
    )
  }

  /** Self-managed source whose transfer takes [runTime] of [timeSource] time. */
  private inner class FixtureSource(
    private val runTime: Duration,
    private val started: CompletableDeferred<Unit>? = null,
    private val finishes: Boolean = true,
    private val totalBytes: Long = 4,
    selfManaged: Boolean = true,
  ) : DownloadSource {
    override val type = FIXTURE
    override val managesOwnFileIo = selfManaged
    override fun canHandle(url: String) = true
    override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = totalBytes, supportsResume = true,
      suggestedFileName = "fixture", maxSegments = 1,
    )
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) = transfer()
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      transfer()

    private suspend fun transfer() {
      timeSource += runTime
      started?.complete(Unit)
      if (!finishes) awaitCancellation()
    }
  }

  private companion object {
    const val FIXTURE = "fixture"
    const val OUTPUT = "/tmp/ketch-download-time-output"
    val FINISHED_AT = Instant.fromEpochMilliseconds(1_700_000_000_123)
  }
}
