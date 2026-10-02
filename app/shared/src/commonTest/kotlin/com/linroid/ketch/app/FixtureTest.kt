package com.linroid.ketch.app

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.config.ConfigStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext

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

/** An app over one device, "[deviceName]", whose engine is [api], run by the test's scheduler. */
internal fun TestScope.testController(
  api: KetchApi,
  configStore: ConfigStore? = null,
  deviceName: String = "This Mac",
  context: CoroutineContext = StandardTestDispatcher(testScheduler),
): AppController = AppController(
  instanceManager = InstanceManager(
    factory = InstanceFactory(deviceName = deviceName, embeddedFactory = { api }),
    configStore = configStore,
  ),
  context = context,
)

/**
 * A context for an app under test whose loops run as a child of the background scope, so a
 * failed assertion never leaves them running.
 */
internal fun TestScope.backgroundChild(): CoroutineContext =
  backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])
