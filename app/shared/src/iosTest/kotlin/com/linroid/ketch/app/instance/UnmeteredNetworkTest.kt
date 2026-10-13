package com.linroid.ketch.app.instance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnmeteredNetworkTest {
  @Test
  fun pathAllowsUploads_usableWifi_allows() {
    assertTrue(pathAllowsUploads(satisfied = true, expensive = false, constrained = false))
  }

  @Test
  fun pathAllowsUploads_cellularOrHotspot_refuses() {
    assertFalse(pathAllowsUploads(satisfied = true, expensive = true, constrained = false))
  }

  @Test
  fun pathAllowsUploads_lowDataMode_refuses() {
    assertFalse(pathAllowsUploads(satisfied = true, expensive = false, constrained = true))
  }

  @Test
  fun pathAllowsUploads_noUsableNetwork_refuses() {
    assertFalse(pathAllowsUploads(satisfied = false, expensive = false, constrained = false))
  }
}
