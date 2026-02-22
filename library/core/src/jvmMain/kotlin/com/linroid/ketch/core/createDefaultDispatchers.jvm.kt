@file:OptIn(DelicateCoroutinesApi::class)

package com.linroid.ketch.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext

internal actual fun createMainDispatcher(): CoroutineDispatcher =
  newSingleThreadContext("ketch-main")

internal actual fun createNetworkDispatcher(poolSize: Int): CoroutineDispatcher =
  newFixedThreadPoolContext(poolSize, "ketch-network")

internal actual fun createIoDispatcher(poolSize: Int): CoroutineDispatcher =
  newFixedThreadPoolContext(poolSize, "ketch-io")
