package com.linroid.ketch.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun createMainDispatcher(): CoroutineDispatcher =
  Dispatchers.Default

@Suppress("UNUSED_PARAMETER")
internal actual fun createNetworkDispatcher(poolSize: Int): CoroutineDispatcher =
  Dispatchers.Default

@Suppress("UNUSED_PARAMETER")
internal actual fun createIoDispatcher(poolSize: Int): CoroutineDispatcher =
  Dispatchers.Default
