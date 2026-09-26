package com.linroid.ketch.app

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.countChoices
import com.linroid.ketch.app.state.formatSpeedAmount
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.parseSpeedLimit
import com.linroid.ketch.app.state.portError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsChoicesTest {

  @Test
  fun `a hand-edited value joins the presets in order`() {
    assertEquals(listOf(1, 2, 7, 8, 0), countChoices(listOf(1, 2, 8, 0), 7))
  }

  @Test
  fun `unlimited sorts last and is not duplicated`() {
    assertEquals(listOf(1, 4, 0), countChoices(listOf(0, 4, 1), 0))
  }

  @Test
  fun `speed limits read in the largest sensible unit`() {
    assertEquals("Unlimited", formatSpeedLimit(SpeedLimit.Unlimited))
    assertEquals("512 KB/s", formatSpeedLimit(SpeedLimit.kbps(512)))
    assertEquals("2 MB/s", formatSpeedLimit(SpeedLimit.mbps(2)))
    assertEquals("1.5 MB/s", formatSpeedLimit(SpeedLimit.kbps(1536)))
  }

  @Test
  fun `typed amounts accept decimals and round trip`() {
    val limit = parseSpeedLimit("1.5", SpeedUnit.MB)
    assertEquals(SpeedLimit.kbps(1536), limit)
    assertEquals("1.5", formatSpeedAmount(limit!!, SpeedUnit.MB))
    assertEquals("1536", formatSpeedAmount(limit, SpeedUnit.KB))
  }

  @Test
  fun `zero negative and garbage amounts are rejected`() {
    assertNull(parseSpeedLimit("0", SpeedUnit.KB))
    assertNull(parseSpeedLimit("-5", SpeedUnit.MB))
    assertNull(parseSpeedLimit("", SpeedUnit.MB))
    assertNull(parseSpeedLimit("fast", SpeedUnit.MB))
    // Less than one byte per second rounds down to nothing.
    assertNull(parseSpeedLimit("0.0001", SpeedUnit.KB))
  }

  @Test
  fun `ports outside 1 to 65535 are explained`() {
    assertNull(portError("8642"))
    assertNull(portError(" 1 "))
    assertNotNull(portError("0"))
    assertNotNull(portError("65536"))
    assertNotNull(portError(""))
    assertNotNull(portError("http"))
  }
}
