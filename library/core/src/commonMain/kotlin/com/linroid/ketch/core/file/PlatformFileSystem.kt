package com.linroid.ketch.core.file

import okio.FileSystem

/**
 * Platform-specific [FileSystem] instance.
 *
 * Maps to [FileSystem.SYSTEM] on JVM, Android, and iOS,
 * [okio.NodeJsFileSystem] on JS, and [okio.WasiFileSystem] on WasmWasi.
 */
internal expect val platformFileSystem: FileSystem
