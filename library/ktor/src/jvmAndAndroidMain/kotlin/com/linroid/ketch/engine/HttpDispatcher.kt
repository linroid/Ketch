package com.linroid.ketch.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the HTTP clients [KtorHttpEngine] builds block, on daemon threads of their own
 * (`ketch-http-N`) rather than the application's `Dispatchers.IO`: OkHttp reads each response
 * body here for as long as it streams, and CIO resolves host names and runs its connections here.
 * A thread starts only when every one is busy and ends once idle for 30 seconds; at most
 * [HTTP_THREADS] run at once, as `Dispatchers.IO` allowed. ktor-network still runs every socket's
 * own read and write loops on `Dispatchers.IO`, which nothing can change.
 */
internal val httpDispatcher: CoroutineDispatcher by lazy {
  val count = AtomicInteger()
  ThreadPoolExecutor(0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
    Thread(runnable, "ketch-http-${count.incrementAndGet()}").apply { isDaemon = true }
  }.asCoroutineDispatcher().limitedParallelism(HTTP_THREADS)
}

private const val HTTP_THREADS = 64
