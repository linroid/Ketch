package com.linroid.ketch.app.ui.toolbar

import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchToolbarTest {

  @Test
  fun capLabel_fiveMegabytesPerSecond_readsWholeMegabytes() {
    assertEquals("/ 5 MB/s", capLabel(SpeedLimit.mbps(5).bytesPerSecond))
  }

  @Test
  fun capLabel_belowOneMegabyte_readsKilobytes() {
    assertEquals("/ 512 KB/s", capLabel(SpeedLimit.kbps(512).bytesPerSecond))
  }

  @Test
  fun capLabel_noCap_readsInfinity() {
    assertEquals("/ ∞", capLabel(null))
  }
}
