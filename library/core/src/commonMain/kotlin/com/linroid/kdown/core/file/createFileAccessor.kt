package com.linroid.kdown.core.file

/**
 * Creates a platform-appropriate [FileAccessor] for the given [path].
 *
 * On JVM/iOS this returns a [PathFileAccessor] backed by a
 * platform-specific [RandomAccessHandle]. On Android, content URIs
 * are routed to a dedicated `ContentUriFileAccessor`. On WasmJs,
 * a stub that throws [UnsupportedOperationException] is returned.
 */
expect fun createFileAccessor(path: String): FileAccessor
