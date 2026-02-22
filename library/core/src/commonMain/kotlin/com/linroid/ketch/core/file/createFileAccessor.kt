package com.linroid.ketch.core.file

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates a platform-appropriate [FileAccessor] for the given [path].
 *
 * On JVM/iOS this returns a [PathFileAccessor] backed by a
 * platform-specific [RandomAccessHandle]. On Android, content URIs
 * are routed to a dedicated `ContentUriFileAccessor`. On WasmJs,
 * a stub that throws [UnsupportedOperationException] is returned.
 *
 * @param path file system path (or content URI on Android)
 * @param dispatcher dispatcher for blocking file I/O operations
 */
expect fun createFileAccessor(
  path: String,
  dispatcher: CoroutineDispatcher,
): FileAccessor
