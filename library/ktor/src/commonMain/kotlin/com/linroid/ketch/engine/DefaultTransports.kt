package com.linroid.ketch.engine

/** The transports of a [KtorHttpEngine] created without a client: the platform's own. */
internal expect fun defaultTransports(): HttpTransports

/**
 * How long the engine's own clients may take to open a connection, where the platform's client
 * takes a connect timeout (not Darwin). Requests and reads have no timeout: a download's transfer
 * watchdog drops a transfer that stalls.
 */
internal const val CONNECT_TIMEOUT_MILLIS: Long = 30_000
