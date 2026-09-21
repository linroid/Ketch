package com.linroid.ketch.engine

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = CIO
