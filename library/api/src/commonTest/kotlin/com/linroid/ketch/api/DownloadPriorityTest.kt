package com.linroid.ketch.api

import kotlin.test.Test
import kotlin.test.assertTrue

class DownloadPriorityTest {

  @Test
  fun ordinal_ordering() {
    assertTrue(DownloadPriority.LOW.ordinal < DownloadPriority.NORMAL.ordinal)
    assertTrue(DownloadPriority.NORMAL.ordinal < DownloadPriority.HIGH.ordinal)
    assertTrue(DownloadPriority.HIGH.ordinal < DownloadPriority.URGENT.ordinal)
  }
}
