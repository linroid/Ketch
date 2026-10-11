package com.linroid.ketch.core

/** Most threads [KetchDispatchers.Default] runs network operations on. */
internal const val DEFAULT_NETWORK_THREADS = 8

/** Most threads [KetchDispatchers.Default] runs blocking file I/O on. */
internal const val DEFAULT_DISK_THREADS = 8

/** Creates [KetchDispatchers.Default], once per process. */
internal expect fun createDefaultDispatchers(): KetchDispatchers
