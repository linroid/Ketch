package com.linroid.ketch.app.components

import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
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
  fun connectionText_autoAndCount_readAsDesigned() = runTest {
    assertEquals("Auto (4)", connectionText(0, autoValue = 4).load())
    assertEquals("Auto", connectionText(0).load())
    assertEquals("8", connectionText(8, autoValue = 4).load())
  }

  @Test
  fun stepperCount_valueLabel_namesWhatItCounts() = runTest {
    assertEquals("8 connections", StepperCount.Connections.valueLabel(8).load())
    assertEquals("1 connection", StepperCount.Connections.valueLabel(1).load())
    assertEquals("Auto (4) connections", StepperCount.Connections.valueLabel(0, 4).load())
    assertEquals("50 peers", StepperCount.Peers.valueLabel(50).load())
    assertEquals("Fewer peers", StepperCount.Peers.fewerLabel.load())
    assertEquals("More connections", StepperCount.Connections.moreLabel.load())
  }
}
