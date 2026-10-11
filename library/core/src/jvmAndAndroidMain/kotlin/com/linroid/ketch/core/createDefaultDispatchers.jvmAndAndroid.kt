package com.linroid.ketch.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal actual fun createDefaultDispatchers(): KetchDispatchers {
  val network = ketchThreadPool("ketch-network", DEFAULT_NETWORK_THREADS)
  return KetchDispatchers(
    main = network,
    network = network,
    disk = ketchThreadPool("ketch-disk", DEFAULT_DISK_THREADS),
  )
}

/**
 * A pool of at most [threads] daemon threads named `<name>-N`. A thread starts only when work
 * arrives and every thread is busy, so the pool holds as many as ran at once, and each ends once
 * idle for [keepAlive], so an idle pool holds none.
 */
internal fun ketchThreadPool(
  name: String,
  threads: Int,
  keepAlive: Duration = 30.seconds,
): CoroutineDispatcher {
  val count = AtomicInteger()
  // A fixed pool would start a thread for each task until it had all of them, even with others
  // idle; this one hands work to an idle thread first, and the view caps how many run at once.
  val executor = ThreadPoolExecutor(
    0,
    Int.MAX_VALUE,
    keepAlive.inWholeMilliseconds,
    TimeUnit.MILLISECONDS,
    SynchronousQueue(),
  ) { runnable ->
    Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
  }
  return executor.asCoroutineDispatcher().limitedParallelism(threads)
}
