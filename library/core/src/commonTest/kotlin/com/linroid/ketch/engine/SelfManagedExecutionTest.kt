package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.encodeState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer

class SelfManagedExecutionTest {
  @Test
  fun zeroByteSelectionReachesSourceAndCancellationSavesFinalState() = runTest {
    val started = CompletableDeferred<Unit>()
    var finalState = false
    var calls = 0
    val source = object : DownloadSource<String> {
      override val stateSerializer = String.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = 100, supportsResume = true,
          suggestedFileName = "fixture", maxSegments = 2,
          files = listOf(SourceFile("0", "empty", 0), SourceFile("1", "other", 100)),
          selectionMode = FileSelectionMode.MULTIPLE,
        )
      }
      // Asked as the run starts, before any state exists, and once more as it stops.
      override suspend fun store(context: DownloadContext): String? {
        calls++
        return if (finalState) "final" else null
      }
      override suspend fun download(context: DownloadContext, state: String?) {
        assertEquals("/tmp/ketch-self-managed-output", context.outputPath)
        started.complete(Unit)
        try { awaitCancellation() } finally { finalState = true }
      }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input", selectedFileIds = setOf("0"),
      destination = Destination("/tmp/ketch-self-managed-output"))
    val handle = object : TaskHandle {
      override val taskId = "self-managed"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val execution = DownloadExecution(handle, SourceResolver(listOf(source)),
      DefaultFileNameResolver(), DownloadConfig(saveIntervalMs = 60_000), SpeedLimiter.Unlimited,
      KetchDispatchers(main = Dispatchers.Default, network = Dispatchers.Default,
        disk = Dispatchers.Default))
    val job = launch { execution.execute() }
    started.await()
    assertEquals(0L, execution.totalBytes)
    assertEquals(null, handle.record.value.sourceState)
    job.cancelAndJoin()
    assertEquals(2, calls)
    assertEquals(source.encodeState("final"), handle.record.value.sourceState)
  }

  @Test
  fun execute_unknownSize_failsWithoutCallingSource() = runTest {
    var downloads = 0
    val source = object : DownloadSource<Unit> {
      override val stateSerializer = Unit.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = -1, supportsResume = false,
          suggestedFileName = "fixture", maxSegments = 1,
        )
      }
      override suspend fun download(context: DownloadContext, state: Unit?) { downloads++ }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-self-managed-unknown"))
    val handle = object : TaskHandle {
      override val taskId = "self-managed-unknown"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val execution = DownloadExecution(handle, SourceResolver(listOf(source)),
      DefaultFileNameResolver(), DownloadConfig.Default, SpeedLimiter.Unlimited,
      KetchDispatchers(main = Dispatchers.Default, network = Dispatchers.Default,
        disk = Dispatchers.Default))

    assertFailsWith<KetchError.SourceError> { execution.execute() }
    assertEquals(0, downloads)
  }

  @Test
  fun immediateResumeWaitsForCheckpointAndIgnoresLateProgress() = runTest {
    val started = CompletableDeferred<Unit>()
    val checkpointEntered = CompletableDeferred<Unit>()
    val allowCheckpoint = CompletableDeferred<Unit>()
    val resumed = CompletableDeferred<Unit>()
    var saved = false
    val source = object : DownloadSource<String> {
      override val stateSerializer = String.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = 4, supportsResume = true,
          suggestedFileName = "fixture", maxSegments = 1,
        )
      }
      override suspend fun store(context: DownloadContext) =
        if (saved) "final" else "initial"
      override suspend fun download(context: DownloadContext, state: String?) {
        if (state != null) {
          assertEquals("final", state)
          assertTrue(saved)
          context.segments.value = listOf(Segment(0, 0, 3, 4))
          resumed.complete(Unit)
          return
        }
        context.segments.value = listOf(Segment(0, 0, 3, 1))
        context.onProgress(1, 4)
        started.complete(Unit)
        try { awaitCancellation() } finally {
          withContext(NonCancellable) {
            context.onProgress(2, 4)
            checkpointEntered.complete(Unit)
            allowCheckpoint.await()
            saved = true
          }
        }
      }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input",
      destination = Destination("/tmp/ketch-pause-handoff"))
    val handle = object : TaskHandle {
      override val taskId = "handoff"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher))
    try {
      coordinator.start(handle)
      started.await()
      val pause = launch { coordinator.pause(handle.taskId) }
      checkpointEntered.await()
      assertTrue(handle.mutableState.value is DownloadState.Paused,
        "Late progress replaced the paused state: ${handle.mutableState.value}")
      val resume = async { coordinator.resume(handle) }
      yield()
      assertFalse(pause.isCompleted)
      assertFalse(resume.isCompleted)
      allowCheckpoint.complete(Unit)
      pause.join()
      assertTrue(resume.await())
      resumed.await()
    } finally {
      allowCheckpoint.complete(Unit)
      coordinator.close()
    }
  }

  @Test
  fun resume_withoutSavedSegments_keepsTheChosenOutputPath() = runTest {
    val outputs = mutableListOf<String?>()
    val source = object : DownloadSource<String> {
      override val stateSerializer = String.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = 4, supportsResume = true,
          suggestedFileName = "fixture", maxSegments = 1,
        )
      }
      override suspend fun download(context: DownloadContext, state: String?) {
        outputs += context.outputPath
        context.segments.value = listOf(Segment(0, 0, 3, 4))
      }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input")
    // As a process that exited right after the start left it: a path, but no segments yet.
    val handle = object : TaskHandle {
      override val taskId = "unsaved"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request,
        outputPath = "/tmp/ketch-unsaved-output", state = TaskState.DOWNLOADING,
        sourceType = "fixture", createdAt = now, updatedAt = now)) {}
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = DownloadCoordinator(SourceResolver(listOf(source)),
      { DownloadConfig(saveIntervalMs = 60_000) }, DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher))
    try {
      assertTrue(coordinator.resume(handle))
      coordinator.awaitCompletion(handle.taskId)
      assertEquals(listOf<String?>("/tmp/ketch-unsaved-output"), outputs)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun retry_continuesFromTheLatestStoredState() = runTest {
    val received = mutableListOf<String?>()
    val source = object : DownloadSource<String> {
      override val stateSerializer = String.serializer()
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
      override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
        val url = (input as ResolveInput.Url).url
        return ResolvedSource(
          url = url, sourceType = type, totalBytes = 4, supportsResume = true,
          suggestedFileName = "fixture", maxSegments = 1,
        )
      }
      override suspend fun store(context: DownloadContext) = "stored"
      override suspend fun download(context: DownloadContext, state: String?) {
        received += state
        if (received.size == 1) throw KetchError.Network()
        context.segments.value = listOf(Segment(0, 0, 3, 4))
      }
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input", destination = Destination("/tmp/ketch-retry"))
    val handle = object : TaskHandle {
      override val taskId = "retry"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val execution = DownloadExecution(handle, SourceResolver(listOf(source)),
      DefaultFileNameResolver(), DownloadConfig(retryCount = 1, saveIntervalMs = 60_000),
      SpeedLimiter.Unlimited, KetchDispatchers(dispatcher, dispatcher, dispatcher))

    execution.execute()

    // A fresh run starts from nothing; its retry checks what the first attempt wrote.
    assertEquals(listOf(null, "stored"), received)
  }
}
