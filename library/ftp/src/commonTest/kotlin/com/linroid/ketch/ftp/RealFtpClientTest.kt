package com.linroid.ketch.ftp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealFtpClientTest {

  // --- EPSV port parsing ---

  @Test
  fun parseEpsvPort_standardResponse() {
    val port = RealFtpClient.parseEpsvPort(
      "Entering Extended Passive Mode (|||12345|)"
    )
    assertEquals(12345, port)
  }

  @Test
  fun parseEpsvPort_differentPort() {
    val port = RealFtpClient.parseEpsvPort(
      "Entering Extended Passive Mode (|||50000|)"
    )
    assertEquals(50000, port)
  }

  @Test
  fun parseEpsvPort_port1() {
    val port = RealFtpClient.parseEpsvPort(
      "Entering Extended Passive Mode (|||1|)"
    )
    assertEquals(1, port)
  }

  @Test
  fun parseEpsvPort_noMatch_returnsNull() {
    assertNull(RealFtpClient.parseEpsvPort("OK"))
  }

  @Test
  fun parseEpsvPort_malformedParens_returnsNull() {
    assertNull(
      RealFtpClient.parseEpsvPort("Entering (||12345|)")
    )
  }

  @Test
  fun parseEpsvPort_emptyMessage_returnsNull() {
    assertNull(RealFtpClient.parseEpsvPort(""))
  }

  // --- PASV address parsing ---

  @Test
  fun parsePasvAddress_standardResponse() {
    val result = RealFtpClient.parsePasvAddress(
      "Entering Passive Mode (192,168,1,1,4,1)"
    )
    assertEquals("192.168.1.1" to 1025, result)
  }

  @Test
  fun parsePasvAddress_highPort() {
    // port = 234*256 + 56 = 59960 + 56 = 60000 - let's use 234,96
    // 234*256 + 96 = 59904 + 96 = 60000
    val result = RealFtpClient.parsePasvAddress(
      "Entering Passive Mode (10,0,0,1,234,96)"
    )
    assertEquals("10.0.0.1", result?.first)
    assertEquals(234 * 256 + 96, result?.second)
  }

  @Test
  fun parsePasvAddress_port21() {
    // port = 0*256 + 21 = 21
    val result = RealFtpClient.parsePasvAddress(
      "Entering Passive Mode (127,0,0,1,0,21)"
    )
    assertEquals("127.0.0.1" to 21, result)
  }

  @Test
  fun parsePasvAddress_portZero() {
    val result = RealFtpClient.parsePasvAddress(
      "Entering Passive Mode (10,0,0,1,0,0)"
    )
    assertEquals("10.0.0.1" to 0, result)
  }

  @Test
  fun parsePasvAddress_maxPort() {
    // port = 255*256 + 255 = 65535
    val result = RealFtpClient.parsePasvAddress(
      "Entering Passive Mode (10,0,0,1,255,255)"
    )
    assertEquals("10.0.0.1" to 65535, result)
  }

  @Test
  fun parsePasvAddress_noMatch_returnsNull() {
    assertNull(RealFtpClient.parsePasvAddress("OK"))
  }

  @Test
  fun parsePasvAddress_emptyMessage_returnsNull() {
    assertNull(RealFtpClient.parsePasvAddress(""))
  }

  @Test
  fun parsePasvAddress_incompleteNumbers_returnsNull() {
    assertNull(
      RealFtpClient.parsePasvAddress(
        "Entering Passive Mode (192,168,1)"
      )
    )
  }
}
