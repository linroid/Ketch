package com.linroid.ketch.core.file

import okio.FileSystem

/**
 * Platform-specific [FileSystem] instance.
 *
 * Maps to [FileSystem.SYSTEM] on JVM, Android, and iOS.
 * On WasmJs, accessing this property throws [UnsupportedOperationException]
 * because browser environments have no local file system.
 */
internal expect val platformFileSystem: FileSystem
