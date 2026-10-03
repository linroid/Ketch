package com.linroid.ketch.app.util

import kotlin.time.Duration

fun extractFilename(url: String): String {
  val path = url.trim()
    .substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
  return path.ifBlank { "" }
}

/** Bytes per second over [duration], or `null` when it is too short to measure. */
fun averageSpeed(bytes: Long, duration: Duration): Long? {
  val millis = duration.inWholeMilliseconds
  return if (millis > 0) bytes * 1000 / millis else null
}
