package com.linroid.ketch.ftp

import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.decodeResumeState
import kotlin.test.Test
import kotlin.test.assertEquals

class FtpResumeStateTest {
  private val source = FtpDownloadSource()

  @Test
  fun buildResumeState_keepsServerModificationTime() {
    val resolved = ResolvedSource(
      url = "ftp://example.com/file.bin",
      sourceType = FtpDownloadSource.TYPE,
      totalBytes = 4096,
      supportsResume = true,
      suggestedFileName = "file.bin",
      maxSegments = 4,
      metadata = mapOf(FtpDownloadSource.META_MDTM to "20250115120000"),
    )

    assertEquals(FtpResumeState(4096, "20250115120000"), source.buildResumeState(resolved, 4096))
  }

  @Test
  fun decodeResumeState_withoutMdtm_readsNull() {
    val saved = SourceResumeState(FtpDownloadSource.TYPE, """{"totalBytes":4096}""")

    assertEquals(FtpResumeState(4096, null), source.decodeResumeState(saved))
  }
}
