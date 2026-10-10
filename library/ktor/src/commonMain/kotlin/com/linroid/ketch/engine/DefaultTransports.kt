package com.linroid.ketch.engine

/** The transports of a [KtorHttpEngine] created without a client: the platform's own. */
internal expect fun defaultTransports(): HttpTransports
