package com.linroid.ketch.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionStepperTest {

  @Test
  fun stepConnections_fromAuto_startsAtTheResolvedCount() {
    assertEquals(5, stepConnections(current = 0, delta = 1, autoValue = 4))
    assertEquals(3, stepConnections(current = 0, delta = -1, autoValue = 4))
  }

  @Test
  fun stepConnections_fromAutoWithoutResolvedCount_startsAtTheFirst() {
    assertEquals(2, stepConnections(current = 0, delta = 1))
  }

  @Test
  fun stepConnections_atTheEnds_staysInRange() {
    assertEquals(1, stepConnections(current = 1, delta = -1))
    assertEquals(32, stepConnections(current = 32, delta = 1))
  }

  @Test
  fun stepConnections_peerLimit_movesByTenWithinItsRange() {
    assertEquals(60, stepConnections(50, 1, PeerLimitRange, PEER_LIMIT_STEP))
    assertEquals(512, stepConnections(509, 1, PeerLimitRange, PEER_LIMIT_STEP))
    assertEquals(1, stepConnections(5, -1, PeerLimitRange, PEER_LIMIT_STEP))
  }

  @Test
  fun connectionLabel_autoAndCount_readAsDesigned() {
    assertEquals("Auto (4)", connectionLabel(0, autoValue = 4))
    assertEquals("Auto", connectionLabel(0))
    assertEquals("8", connectionLabel(8, autoValue = 4))
  }
}
