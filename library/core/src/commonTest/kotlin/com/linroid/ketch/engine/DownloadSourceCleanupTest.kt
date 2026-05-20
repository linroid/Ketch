package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadSourceCleanupTest {

  private class RecordingFileAccessor(
    private val onDelete: suspend () -> Unit = {},
  ) : FileAccessor {
    var deleteCount = 0
    override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
    override suspend fun flush() = Unit
    override fun close() = Unit
    override suspend fun delete() {
      deleteCount++
      onDelete()
    }
    override suspend fun size(): Long = 0
    override suspend fun preallocate(size: Long) = Unit
  }

  private class StubSource : DownloadSource {
    override val type = "stub"
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
  }

  private fun context(accessor: FileAccessor): DownloadContext =
    DownloadContext(
      taskId = "t",
      url = "https://example.com/f",
      request = DownloadRequest(
        url = "https://example.com/f",
        destination = Destination("/tmp/"),
      ),
      fileAccessor = accessor,
      segments = MutableStateFlow(emptyList()),
      onProgress = { _, _ -> },
      throttle = { _ -> },
      headers = emptyMap(),
    )

  @Test
  fun defaultCleanup_deletesFileAccessor() = runTest {
    val accessor = RecordingFileAccessor()
    val source = StubSource()

    source.cleanup(context(accessor), resumeState = null)

    assertEquals(1, accessor.deleteCount)
  }

  @Test
  fun defaultCleanup_swallowsDeleteFailure() = runTest {
    val accessor = RecordingFileAccessor(
      onDelete = { throw RuntimeException("permission denied") },
    )
    val source = StubSource()

    // Should not throw — best-effort cleanup.
    source.cleanup(context(accessor), resumeState = null)

    assertEquals(1, accessor.deleteCount)
  }

  @Test
  fun defaultCleanup_ignoresResumeState() = runTest {
    val accessor = RecordingFileAccessor()
    val source = StubSource()

    source.cleanup(
      context = context(accessor),
      resumeState = SourceResumeState("stub", "{}"),
    )

    assertEquals(1, accessor.deleteCount)
  }
}
