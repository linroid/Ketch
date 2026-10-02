package com.linroid.ketch.app

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Runs [block] over the fixture [create] makes and [close]s it even when an assertion fails,
 * since the timers of an open app would otherwise keep the test's scheduler from going idle.
 */
internal fun <F> fixtureTest(
  create: TestScope.() -> F,
  close: (F) -> Unit,
  block: suspend TestScope.(F) -> Unit,
): TestResult = runTest {
  val fixture = create()
  try {
    block(fixture)
  } finally {
    close(fixture)
  }
}
