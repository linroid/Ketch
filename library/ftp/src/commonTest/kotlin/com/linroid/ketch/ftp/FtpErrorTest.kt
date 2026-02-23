package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FtpErrorTest {

  // --- Service unavailable / connection issues -> Network ---

  @Test
  fun fromReply_421_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(421, "Service not available")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fromReply_425_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(425, "Can't open data connection")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fromReply_426_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(426, "Connection closed, transfer aborted")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  // --- Authentication failures (4xx) -> Network (transient) ---

  @Test
  fun fromReply_430_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(430, "Invalid username or password")
    )
    assertIs<KetchError.Network>(error)
  }

  // --- Authentication failures (5xx) -> SourceError ---

  @Test
  fun fromReply_530_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(530, "Not logged in")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  // --- File not found / unavailable ---

  @Test
  fun fromReply_450_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(450, "File unavailable")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fromReply_550_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(550, "File not found")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  // --- Server errors ---

  @Test
  fun fromReply_451_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(451, "Requested action aborted: local error")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fromReply_551_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(551, "Requested action aborted: page type unknown")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  // --- Storage / disk errors -> Disk ---

  @Test
  fun fromReply_452_mapsToDisk() {
    val error = FtpError.fromReply(
      FtpReply(452, "Insufficient storage space")
    )
    assertIs<KetchError.Disk>(error)
  }

  @Test
  fun fromReply_552_mapsToDisk() {
    val error = FtpError.fromReply(
      FtpReply(552, "Exceeded storage allocation")
    )
    assertIs<KetchError.Disk>(error)
  }

  // --- Filename not allowed (5xx) -> SourceError ---

  @Test
  fun fromReply_553_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(553, "Requested action not taken: file name not allowed")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  // --- Other 4xx -> Network (transient) ---

  @Test
  fun fromReply_other4xx_mapsToNetwork() {
    val error = FtpError.fromReply(
      FtpReply(434, "Host unavailable")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  // --- Other 5xx -> SourceError ---

  @Test
  fun fromReply_500_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(500, "Syntax error, command unrecognized")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  @Test
  fun fromReply_501_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(501, "Syntax error in parameters")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  @Test
  fun fromReply_502_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(502, "Command not implemented")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  @Test
  fun fromReply_504_mapsToSourceError() {
    val error = FtpError.fromReply(
      FtpReply(504, "Command not implemented for that parameter")
    )
    assertIs<KetchError.SourceError>(error)
    assertEquals("ftp", error.sourceType)
  }

  // --- Unexpected codes -> Unknown ---

  @Test
  fun fromReply_unexpectedCode_mapsToUnknown() {
    val error = FtpError.fromReply(
      FtpReply(600, "Unexpected")
    )
    assertIs<KetchError.Unknown>(error)
  }

  @Test
  fun fromReply_positiveCode_mapsToUnknown() {
    val error = FtpError.fromReply(
      FtpReply(220, "Welcome")
    )
    assertIs<KetchError.Unknown>(error)
  }

  // --- Retryability ---

  @Test
  fun fromReply_authFailure530_notRetryable() {
    val error = FtpError.fromReply(
      FtpReply(530, "Not logged in")
    )
    assertIs<KetchError.SourceError>(error)
    assertTrue(!error.isRetryable)
  }

  @Test
  fun fromReply_fileNotFound550_notRetryable() {
    val error = FtpError.fromReply(
      FtpReply(550, "File not found")
    )
    assertIs<KetchError.SourceError>(error)
    assertTrue(!error.isRetryable)
  }

  @Test
  fun fromReply_4xxTransient_retryable() {
    val error = FtpError.fromReply(
      FtpReply(451, "Local error")
    )
    assertIs<KetchError.Network>(error)
    assertTrue(error.isRetryable)
  }

  @Test
  fun fromReply_diskError_notRetryable() {
    val error = FtpError.fromReply(
      FtpReply(452, "Insufficient storage")
    )
    assertIs<KetchError.Disk>(error)
    assertTrue(!error.isRetryable)
  }

  // --- fromException ---

  @Test
  fun fromException_wrapsAsNetwork() {
    val cause = RuntimeException("Connection refused")
    val error = FtpError.fromException(cause)
    assertIs<KetchError.Network>(error)
    assertEquals(cause, error.cause)
    assertTrue(error.isRetryable)
  }

  // --- Error message includes FTP code ---

  @Test
  fun fromReply_sourceError_causeContainsFtpCode() {
    val error = FtpError.fromReply(
      FtpReply(550, "No such file")
    )
    assertIs<KetchError.SourceError>(error)
    assertTrue(error.cause!!.message!!.contains("550"))
    assertTrue(error.cause!!.message!!.contains("No such file"))
  }
}
