package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class AiDiscoverySettingsTest {
  @Test
  fun connectedCopy_timedTest_namesModelAndTenthsOfASecond() = runTest {
    assertEquals(
      "Connected · claude-sonnet-5 responded in 1.2 s",
      connectedCopy("claude-sonnet-5", 1_249.milliseconds).load(),
    )
    assertEquals(
      "Connected · gpt-6 responded in 0.0 s",
      connectedCopy("gpt-6", 40.milliseconds).load(),
    )
  }

  @Test
  fun connectedCopy_untimedOrUnnamed_leavesThoseOut() = runTest {
    assertEquals("Connected · qwen3 responded", connectedCopy("qwen3", null).load())
    assertEquals("Connected · the model responded", connectedCopy("", null).load())
  }
}
