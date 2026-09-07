package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class DownloadCoordinatorCleanupTest {

  private class RecordingSource(
    override val type: String = "rec",
    override val managesOwnFileIo: Boolean = false,
  ) : DownloadSource {
    var lastOutputPath: String? = null
    var cleanupCalls = 0
    var lastResumeState: SourceResumeState? = null
    var lastFileAccessorType: String? = null

    override fun canHandle(url: String): Boolean = true
    override suspend fun resolve(
      url: String,
      properties: Map<String, String>,
    ): ResolvedSource = error("not used")
    override suspend fun download(context: DownloadContext) = Unit
    override suspend fun resume(
      context: DownloadContext,
      resumeState: SourceResumeState,
    ) = Unit
    override fun buildResumeState(
      resolved: ResolvedSource,
      totalBytes: Long,
    ): SourceResumeState = SourceResumeState(type, "")
    override suspend fun cleanup(
      context: DownloadContext,
      resumeState: SourceResumeState?,
    ) {
      lastOutputPath = context.outputPath
      cleanupCalls++
      lastResumeState = resumeState
      lastFileAccessorType =
        context.fileAccessor::class.simpleName
    }
  }

  private fun createCoordinator(source: DownloadSource): DownloadCoordinator =
    DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      config = DownloadConfig(),
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        io = Dispatchers.Default,
      ),
    )

  private fun createHandle(
    sourceType: String?,
    outputPath: String?,
    resumeState: SourceResumeState? = null,
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
      sourceResumeState = resumeState,
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
      override val record = AtomicSaver(record) {}
    }
  }

  @Test
  fun cleanup_invokesSourceWithResumeState() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val resume = SourceResumeState("rec", "payload")
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
      resumeState = resume,
    )

    coordinator.cleanup(handle)

    assertEquals(1, source.cleanupCalls)
    assertEquals(resume, source.lastResumeState)
    assertEquals("/tmp/file.zip", source.lastOutputPath)
  }

  @Test
  fun cleanup_managesOwnFileIo_passesNoOpAccessor() = runTest {
    val source = RecordingSource(managesOwnFileIo = true)
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = "/tmp/file.zip",
    )

    coordinator.cleanup(handle)

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

    coordinator.cleanup(handle)

    assertEquals(0, source.cleanupCalls)
  }

  @Test
  fun cleanup_missingOutputPath_isNoOp() = runTest {
    val source = RecordingSource()
    val coordinator = createCoordinator(source)
    val handle = createHandle(
      sourceType = "rec",
      outputPath = null,
    )

    coordinator.cleanup(handle)

    assertEquals(0, source.cleanupCalls)
  }
}
