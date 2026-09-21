package com.linroid.ketch.engine

import io.ktor.client.engine.HttpClientEngineFactory

// Select explicitly so adding a transport for interface binding does not change the default.
internal expect fun defaultHttpClientEngine(): HttpClientEngineFactory<*>
