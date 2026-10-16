package com.linroid.ketch.api

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Where a download is saved: a file name (`file.zip`), a file path, a folder ending in `/`
 * (`/tmp/downloads/`) or an Android content URI.
 */
@Serializable
@JvmInline
value class Destination(val value: String) {
  override fun toString(): String = value
}

/** Whether this is a file path or a content URI of a file. */
expect fun Destination.isFile(): Boolean

/** Whether this is a folder. */
expect fun Destination.isDirectory(): Boolean

/** Whether this is a bare file name, without folder or scheme. */
expect fun Destination.isName(): Boolean
