package com.linroid.ketch.api

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Represents a download destination. Can be:
 * - A bare filename (e.g., `"file.zip"`)
 * - A relative or absolute file path (e.g., `"/tmp/downloads/file.zip"`)
 * - A directory path (e.g., `"/tmp/downloads/"`)
 * - An Android content URI (e.g., `"content://..."`)
 */
@Serializable
@JvmInline
value class Destination(val value: String) {
  override fun toString(): String = value
}

/**
 * Returns `true` if this destination represents a specific file
 * (an absolute/relative path or a content URI pointing to a file).
 */
expect fun Destination.isFile(): Boolean

/**
 * Returns `true` if this destination represents a directory.
 */
expect fun Destination.isDirectory(): Boolean

/**
 * Returns `true` if this destination is a bare filename with
 * no directory component or URI scheme (e.g., `"file.zip"`).
 */
expect fun Destination.isName(): Boolean
