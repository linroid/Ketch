package com.linroid.ketch.remote

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine

/**
 * Reads the server-sent events of this channel until it ends, passing each event's name and
 * data to [onEvent]. Comments, such as heartbeats, and events without a name are skipped; an
 * event the end of the channel cuts short is still passed on.
 */
internal suspend fun ByteReadChannel.readServerSentEvents(
  onEvent: suspend (name: String, data: String) -> Unit,
) {
  var name: String? = null
  val data = StringBuilder()
  while (true) {
    val line = readLine()
    // A blank line ends an event; so does the end of the stream, leniently.
    if (line.isNullOrEmpty()) {
      val event = name
      name = null
      val text = data.toString()
      data.clear()
      if (event != null) onEvent(event, text)
      if (line == null) return
      continue
    }
    if (line.startsWith(":")) continue
    val value = line.substringAfter(':', "").removePrefix(" ")
    when (line.substringBefore(':')) {
      "event" -> name = value
      "data" -> {
        if (data.isNotEmpty()) data.append('\n')
        data.append(value)
      }
    }
  }
}
