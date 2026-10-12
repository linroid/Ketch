package com.linroid.ketch.ftp

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.decodeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FtpSourceStateTest {
  private val source = FtpDownloadSource()

  @Test
  fun store_keepsServerModificationTime() = runTest {
    val resolved = ResolvedSource(
      url = "ftp://example.com/file.bin",
      sourceType = FtpDownloadSource.TYPE,
      totalBytes = 4096,
      supportsResume = true,
      suggestedFileName = "file.bin",
      maxSegments = 4,
      metadata = mapOf(FtpDownloadSource.META_MDTM to "20250115120000"),
    )

    val context = DownloadContext(
      taskId = "ftp",
      url = resolved.url,
      request = DownloadRequest(resolved.url),
      fileAccessor = MemoryFileAccessor(),
      segments = MutableStateFlow(emptyList()),
      onProgress = { _, _ -> },
      throttle = {},
      headers = emptyMap(),
      preResolved = resolved,
    )

    assertEquals(FtpSourceState(4096, "20250115120000"), source.store(context))
  }

  @Test
  fun decodeResumeState_withoutMdtm_readsNull() {
    val saved = SourceState(FtpDownloadSource.TYPE, """{"totalBytes":4096}""")

    assertEquals(FtpSourceState(4096, null), source.decodeState(saved))
  }
}
