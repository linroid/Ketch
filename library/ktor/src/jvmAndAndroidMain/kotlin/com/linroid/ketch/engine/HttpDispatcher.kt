package com.linroid.ketch.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The daemon threads (`ketch-http-N`) the HTTP clients [KtorHttpEngine] builds run on, rather
 * than the application's `Dispatchers.IO`. A thread starts only when every one is busy and ends
 * once idle for 30 seconds. ktor-network still runs every socket's own read and write loops on
 * `Dispatchers.IO`, which nothing can change.
 */
private val httpThreads: CoroutineDispatcher by lazy {
  val count = AtomicInteger()
  ThreadPoolExecutor(0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
    Thread(runnable, "ketch-http-${count.incrementAndGet()}").apply { isDaemon = true }
  }.asCoroutineDispatcher()
}

/**
 * OkHttp's: it reads each response body here, blocking a thread for as long as it streams, so
 * as many run at once as responses stream, at most [OKHTTP_THREADS], as `Dispatchers.IO` allowed.
 */
internal val httpDispatcher: CoroutineDispatcher by lazy {
  httpThreads.limitedParallelism(OKHTTP_THREADS)
}

/**
 * A CIO client's own slice, [CIO_THREADS] of these threads: its selector loop, which keeps one
 * while the client has sockets, its connections' coroutines, which never block, and its host
 * name lookups, which do. A burst of short tasks would otherwise start a thread for each, as a
 * view starts a worker per queued task up to its limit; each client gets a slice of its own, so
 * no client's selector takes another's.
 */
internal fun cioDispatcher(): CoroutineDispatcher = httpThreads.limitedParallelism(CIO_THREADS)

private const val OKHTTP_THREADS = 64
private const val CIO_THREADS = 8
