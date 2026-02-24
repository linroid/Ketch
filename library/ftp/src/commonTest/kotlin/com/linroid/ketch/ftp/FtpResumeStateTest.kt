package com.linroid.ketch.ftp

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtpResumeStateTest {

  @Test
  fun buildResumeState_setsSourceTypeAndEncodesFields() {
    val resumeState = FtpDownloadSource.buildResumeState(
      totalBytes = 4096,
      mdtm = "20250115120000",
    )
    assertEquals("ftp", resumeState.sourceType)

    val decoded = Json.decodeFromString<FtpResumeState>(
      resumeState.data
    )
    assertEquals(4096, decoded.totalBytes)
    assertEquals("20250115120000", decoded.mdtm)
  }

  @Test
  fun buildResumeState_nullMdtm_encodesCorrectly() {
    val resumeState = FtpDownloadSource.buildResumeState(
      totalBytes = 0,
      mdtm = null,
    )
    assertEquals("ftp", resumeState.sourceType)

    val decoded = Json.decodeFromString<FtpResumeState>(
      resumeState.data
    )
    assertEquals(0, decoded.totalBytes)
    assertNull(decoded.mdtm)
  }
}
