package com.linroid.ketch.ftp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtpReplyTest {

  // --- Boundary tests for range-based classification ---

  @Test
  fun isPositivePreliminary_boundary_199included_200excluded() {
    assertTrue(FtpReply(100, "Start").isPositivePreliminary)
    assertTrue(FtpReply(199, "Edge").isPositivePreliminary)
    assertFalse(FtpReply(200, "OK").isPositivePreliminary)
    assertFalse(FtpReply(99, "Below").isPositivePreliminary)
  }

  @Test
  fun isPositiveCompletion_boundary_200included_300excluded() {
    assertTrue(FtpReply(200, "OK").isPositiveCompletion)
    assertTrue(FtpReply(299, "Edge").isPositiveCompletion)
    assertFalse(FtpReply(300, "Need info").isPositiveCompletion)
    assertFalse(FtpReply(199, "Below").isPositiveCompletion)
  }

  @Test
  fun isError_boundary_400to599() {
    assertFalse(FtpReply(399, "Below").isError)
    assertTrue(FtpReply(400, "Start").isError)
    assertTrue(FtpReply(599, "End").isError)
    assertFalse(FtpReply(600, "Above").isError)
  }

  @Test
  fun isError_excludesNonErrorCodes() {
    assertFalse(FtpReply(150, "Opening data connection").isError)
    assertFalse(FtpReply(226, "Transfer complete").isError)
    assertFalse(FtpReply(331, "Password required").isError)
  }
}
