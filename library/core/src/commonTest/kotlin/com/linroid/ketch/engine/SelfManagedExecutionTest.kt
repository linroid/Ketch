package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadExecution
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class SelfManagedExecutionTest {
  @Test
  fun zeroByteSelectionReachesSourceAndCancellationSavesFinalState() = runTest {
    val started = CompletableDeferred<Unit>()
    var finalState = false
    var calls = 0
    val source = object : DownloadSource {
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(url: String) = true
      override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
        url = url, sourceType = type, totalBytes = 100, supportsResume = true,
        suggestedFileName = "fixture", maxSegments = 2,
        files = listOf(SourceFile("0", "empty", 0), SourceFile("1", "other", 100)),
        selectionMode = FileSelectionMode.MULTIPLE,
      )
      override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState {
        assertEquals(0L, totalBytes)
        return SourceResumeState(type, "initial")
      }
      override suspend fun updateResumeState(context: DownloadContext): SourceResumeState {
        calls++
        assertTrue(finalState)
        return SourceResumeState(type, "final")
      }
      override suspend fun download(context: DownloadContext) {
        assertEquals("/tmp/ketch-self-managed-output", context.outputPath)
        started.complete(Unit)
        try { awaitCancellation() } finally { finalState = true }
      }
      override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
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
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val execution = DownloadExecution(handle, SourceResolver(listOf(source)),
      DefaultFileNameResolver(), DownloadConfig(saveIntervalMs = 60_000), SpeedLimiter.Unlimited,
      KetchDispatchers(main = Dispatchers.Default, network = Dispatchers.Default,
        io = Dispatchers.Default))
    val job = launch { execution.execute() }
    started.await()
    assertEquals(0L, execution.totalBytes)
    job.cancelAndJoin()
    assertEquals(1, calls)
    assertEquals("final", handle.record.value.sourceResumeState?.data)
  }
}
