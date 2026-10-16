package com.linroid.ketch.core

import kotlinx.coroutines.Dispatchers

internal actual fun createDefaultDispatchers(): KetchDispatchers =
  KetchDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default)
