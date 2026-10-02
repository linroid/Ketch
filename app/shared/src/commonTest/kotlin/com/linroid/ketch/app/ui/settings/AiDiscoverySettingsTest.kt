package com.linroid.ketch.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class AiDiscoverySettingsTest {
  @Test
  fun connectedCopy_timedTest_namesModelAndTenthsOfASecond() {
    assertEquals(
      "Connected · claude-sonnet-5 responded in 1.2 s",
      connectedCopy("claude-sonnet-5", 1_249.milliseconds),
    )
    assertEquals("Connected · gpt-6 responded in 0.0 s", connectedCopy("gpt-6", 40.milliseconds))
  }

  @Test
  fun connectedCopy_untimedOrUnnamed_leavesThoseOut() {
    assertEquals("Connected · qwen3 responded", connectedCopy("qwen3", null))
    assertEquals("Connected · the model responded", connectedCopy("", null))
  }
}
