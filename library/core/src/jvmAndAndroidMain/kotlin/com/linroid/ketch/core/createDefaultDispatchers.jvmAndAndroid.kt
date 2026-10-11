package com.linroid.ketch.core

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
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
 * A pool of at most [threads] daemon threads named `<name>-N`, started as work arrives. Each
 * thread ends once idle for [keepAlive], so an idle pool holds none.
 */
internal fun ketchThreadPool(
  name: String,
  threads: Int,
  keepAlive: Duration = 30.seconds,
): ExecutorCoroutineDispatcher {
  val count = AtomicInteger()
  val executor = ThreadPoolExecutor(
    threads,
    threads,
    keepAlive.inWholeMilliseconds,
    TimeUnit.MILLISECONDS,
    LinkedBlockingQueue(),
  ) { runnable ->
    Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
  }
  executor.allowCoreThreadTimeOut(true)
  return executor.asCoroutineDispatcher()
}
