package com.linroid.ketch.core.file

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Creates a platform-appropriate [FileAccessor] for the given [path].
 *
 * On JVM/iOS this returns a [PathFileAccessor] backed by okio's
 * `FileHandle`. On Android, content URIs are routed to a dedicated
 * `ContentUriFileAccessor`. On WasmJs, a stub that throws
 * [UnsupportedOperationException] is returned.
 *
 * @param path file system path (or content URI on Android)
 * @param ioDispatcher dispatcher for blocking file I/O operations
 */
expect fun createFileAccessor(
  path: String,
  ioDispatcher: CoroutineDispatcher,
): FileAccessor
