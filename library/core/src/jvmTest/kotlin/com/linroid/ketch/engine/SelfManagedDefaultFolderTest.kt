package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

// The fallback folder is the platform default, which needs an Android context on Android, so
// this runs on the JVM only.
class SelfManagedDefaultFolderTest {
  @Test
  fun execute_defaultFolderIsAContentUri_givesTheSourceAFilesystemPath() = runTest {
    var outputPath: String? = null
    val source = object : DownloadSource {
      override val type = "fixture"
      override val managesOwnFileIo = true
      override fun canHandle(url: String) = true
      override suspend fun resolve(url: String, properties: Map<String, String>) = ResolvedSource(
        url = url, sourceType = type, totalBytes = 4, supportsResume = true,
        suggestedFileName = "fixture", maxSegments = 1,
      )
      override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
        SourceResumeState(type, "initial")
      override suspend fun download(context: DownloadContext) {
        outputPath = context.outputPath
      }
      override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
    }
    val now = Clock.System.now()
    val request = DownloadRequest("fixture:input")
    val handle = object : TaskHandle {
      override val taskId = "content-default"
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val record = AtomicSaver(TaskRecord(taskId, request, state = TaskState.QUEUED,
        createdAt = now, updatedAt = now)) {}
    }
    val config = DownloadConfig(
      defaultDirectory = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
      saveIntervalMs = 60_000,
    )
    val execution = DownloadExecution(handle, SourceResolver(listOf(source)),
      DefaultFileNameResolver(), config, SpeedLimiter.Unlimited,
      KetchDispatchers(main = Dispatchers.Default, network = Dispatchers.Default,
        io = Dispatchers.Default))

    execution.execute()

    val path = checkNotNull(outputPath)
    assertFalse(path.contains("://"), path)
    assertTrue(path.endsWith("fixture"), path)
  }
}
