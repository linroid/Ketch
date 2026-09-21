package com.linroid.ketch.engine

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = Js
