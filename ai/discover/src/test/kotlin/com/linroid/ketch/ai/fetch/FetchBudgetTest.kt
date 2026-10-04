package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchBudgetTest {

  @Test
  fun tryTakeRequest_afterMax_returnsFalse() = runTest {
    val budget = FetchBudget(maxRequests = 2, maxBytes = 0)

    assertTrue(budget.tryTakeRequest())
    assertTrue(budget.tryTakeRequest())
    assertFalse(budget.tryTakeRequest())
  }

  @Test
  fun reserveBytes_moreThanLeft_grantsRemainderThenZero() = runTest {
    val budget = FetchBudget(maxRequests = 10, maxBytes = 100)

    assertEquals(60L, budget.reserveBytes(60))
    assertEquals(40L, budget.reserveBytes(60))
    assertEquals(0L, budget.reserveBytes(1))
  }

  @Test
  fun returnBytes_unreadBytes_canBeReservedAgain() = runTest {
    val budget = FetchBudget(maxRequests = 10, maxBytes = 100)
    budget.reserveBytes(100)

    budget.returnBytes(30)

    assertEquals(30L, budget.reserveBytes(50))
  }

  @Test
  fun hasRequestLeftAndHasBytesLeft_takeNothing() = runTest {
    val budget = FetchBudget(maxRequests = 1, maxBytes = 10)

    assertTrue(budget.hasRequestLeft())
    assertTrue(budget.hasBytesLeft())
    assertTrue(budget.tryTakeRequest())
    assertEquals(10L, budget.reserveBytes(10))
    assertFalse(budget.hasRequestLeft())
    assertFalse(budget.hasBytesLeft())
  }
}
