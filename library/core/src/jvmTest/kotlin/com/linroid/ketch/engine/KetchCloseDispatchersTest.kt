package com.linroid.ketch.engine

import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class KetchCloseDispatchersTest {
  @Test
  fun close_closesDispatchersOnlyAfterCancelledCoroutinesFinish() = runTest {
    val dispatcher = ClosingDispatcher(StandardTestDispatcher(testScheduler))
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    // The connection sampler's sharing coroutine waits for collectors.
    runCurrent()

    ketch.close()
    // Cancelled coroutines finish on the dispatcher, which must still be open.
    runCurrent()

    withContext(Dispatchers.Default) {
      withTimeout(5.seconds) {
        while (!dispatcher.closed) delay(10)
      }
    }
    assertEquals(0, dispatcher.queuedAtClose)
    assertEquals(0, dispatcher.dispatchesAfterClose.get())
  }

  /**
   * Counts the work still queued when it is closed, and throws, as a closed thread pool does, for
   * work dispatched after [close].
   */
  private class ClosingDispatcher(
    private val delegate: CoroutineDispatcher,
  ) : ExecutorCoroutineDispatcher() {
    private val queued = AtomicInteger()

    @Volatile
    var closed = false
      private set

    @Volatile
    var queuedAtClose = -1
      private set
    val dispatchesAfterClose = AtomicInteger()

    override val executor = Executor { dispatch(EmptyCoroutineContext, it) }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      if (closed) {
        dispatchesAfterClose.incrementAndGet()
        throw IllegalStateException("Dispatcher was closed")
      }
      queued.incrementAndGet()
      delegate.dispatch(context) {
        queued.decrementAndGet()
        block.run()
      }
    }

    override fun close() {
      queuedAtClose = queued.get()
      closed = true
    }
  }
}
