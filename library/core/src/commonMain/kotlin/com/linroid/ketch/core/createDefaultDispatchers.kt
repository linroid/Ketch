package com.linroid.ketch.core

import kotlinx.coroutines.CoroutineDispatcher

/** Creates the single-threaded main/task dispatcher. */
internal expect fun createMainDispatcher(): CoroutineDispatcher

/** Creates a thread pool for network operations. */
internal expect fun createNetworkDispatcher(poolSize: Int): CoroutineDispatcher

/** Creates a thread pool for blocking file I/O. */
internal expect fun createIoDispatcher(poolSize: Int): CoroutineDispatcher
