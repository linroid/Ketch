package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.encodeState
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.builtins.serializer

class DownloadCoordinatorCleanupTest {

  private class RecordingSource(
    override val type: String = "rec",
    override val managesOwnFileIo: Boolean = false,
  ) : DownloadSource<String> {
    override val stateSerializer = String.serializer()
    var lastOutputPath: String? = null
    var cleanupCalls = 0
    var lastState: String? = null
    var lastFileAccessorType: String? = null
    var lastDeleteFiles: Boolean? = null

    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      error("not used")
    override suspend fun download(context: DownloadContext, state: String?) = Unit
    override suspend fun cleanup(
      context: DownloadContext,
      state: String?,
      deleteFiles: Boolean,
    ) {
      lastOutputPath = context.outputPath
      cleanupCalls++
      lastState = state
      lastDeleteFiles = deleteFiles
      lastFileAccessorType =
        context.fileAccessor::class.simpleName
    }
  }

  private fun createCoordinator(source: DownloadSource<*>): DownloadCoordinator =
    DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      config = { DownloadConfig() },
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        disk = Dispatchers.Default,
      ),
    )

  private fun createHandle(
    sourceType: String?,
    outputPath: String?,
    sourceState: SourceState? = null,
  ): TaskHandle {
    val now = Clock.System.now()
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      destination = Destination("/tmp/"),
    )
    val record = TaskRecord(
      taskId = "task-1",
      request = request,
      outputPath = outputPath,
      state = TaskState.COMPLETED,
      sourceType = sourceType,
      sourceState = sourceState,
      createdAt = now,
      updatedAt = now,
    )
    return object : TaskHandle {
      override val taskId = "task-1"
      override val request = request
      override val createdAt: Instant = now
      override val mutableState =
        MutableStateFlow<DownloadState>(DownloadState.Completed(outputPath ?: ""))
      override val mutableSegments =
        MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(record) {}
    }
  }

  @Test
  fun cleanup_invokesSourceWithState() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
      sourceState = source.encodeState("payload"),
    )

    coordinator.cleanup(handle, deleteFiles = true)

    assertEquals(1, source.cleanupCalls)
    assertEquals("payload", source.lastState)
    assertEquals("/tmp/file.zip", source.lastOutputPath)
  }

  @Test
  fun cleanup_unreadableState_passesNull() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
      sourceState = SourceState("rec", "not-json"),
    )

    coordinator.cleanup(handle, deleteFiles = true)

    assertEquals(1, source.cleanupCalls)
    assertNull(source.lastState)
  }

  @Test
  fun cleanup_managesOwnFileIo_passesNoOpAccessor() = runTest {
    val source = RecordingSource(managesOwnFileIo = true)
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
    )

    coordinator.cleanup(handle, deleteFiles = true)

    assertEquals(1, source.cleanupCalls)
    assertEquals("NoOpFileAccessor", source.lastFileAccessorType)
  }

  @Test
  fun cleanup_unknownSourceType_isNoOp() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = null,
      outputPath = "/tmp/file.zip",
    )

    coordinator.cleanup(handle, deleteFiles = true)

    assertEquals(0, source.cleanupCalls)
  }

  @Test
  fun cleanup_missingOutputPath_releasesWithoutDeleting() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = null,
    )

    coordinator.cleanup(handle, deleteFiles = true)

    assertEquals(1, source.cleanupCalls)
    assertEquals(false, source.lastDeleteFiles)
  }

  @Test
  fun cleanup_keepingFiles_stillCallsSource() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
    )

    coordinator.cleanup(handle, deleteFiles = false)

    assertEquals(1, source.cleanupCalls)
    assertEquals(false, source.lastDeleteFiles)
  }
}
