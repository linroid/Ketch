package com.linroid.ketch.engine

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = OkHttp
