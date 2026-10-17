package com.linroid.ketch.app.instance

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.config.RemoteConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class InstanceManagerShutdownTest {

  /** Stops once [stopped] completes, as an engine does once its downloads saved their state. */
  private class Engine : KetchApi by FakeKetchApi("Core") {
    val stopped = CompletableDeferred<Unit>()
    var timeout: Duration? = null

    override suspend fun shutdown(timeout: Duration) {
      this.timeout = timeout
      stopped.await()
    }
  }

  @Test
  fun `shutdown waits for the embedded device and closes the remote ones`() = runTest {
    val engine = Engine()
    val fakes = FakeInstanceFactory(embeddedFactory = { engine })
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = listOf(RemoteConfig(host = "nas.local")),
      context = backgroundScope.coroutineContext,
    )
    runCurrent()

    val shutdown = async { manager.shutdown(3.seconds) }
    runCurrent()

    assertEquals(3.seconds, engine.timeout)
    assertTrue(fakes.remotes.single().closed)
    assertFalse(shutdown.isCompleted)
    engine.stopped.complete(Unit)
    shutdown.await()
  }
}
