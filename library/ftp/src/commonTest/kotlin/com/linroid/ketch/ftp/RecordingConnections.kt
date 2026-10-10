package com.linroid.ketch.ftp

import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec

/** Records the connections a source opens, with the bytes counted on each. */
internal class RecordingConnections : ConnectionReporter {
  val opened = mutableListOf<Connection>()

  override fun open(spec: ConnectionSpec): ConnectionHandle = Connection(spec).also { opened += it }

  class Connection(var spec: ConnectionSpec) : ConnectionHandle {
    var received = 0L
    var closed = false

    override fun received(bytes: Int) {
      received += bytes
    }

    override fun sent(bytes: Int) {}

    override fun describe(spec: ConnectionSpec) {
      this.spec = spec
    }

    override fun close() {
      closed = true
    }
  }
}
