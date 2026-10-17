package com.linroid.ketch.cli

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingConfigTest {

  @Test
  fun ktorEngineChoice_isNotLogged() {
    // Ktor logs which engine a client built without one takes, as `ketch add` and `list` build
    // theirs, at INFO under this name: noise ahead of every command's output.
    val engineChoice = LoggerFactory.getLogger("HttpClient")

    assertFalse(engineChoice.isInfoEnabled)
    assertTrue(engineChoice.isWarnEnabled)
  }
}
