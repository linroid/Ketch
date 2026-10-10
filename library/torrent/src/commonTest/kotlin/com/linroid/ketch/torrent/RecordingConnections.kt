package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Records the connections a torrent opens, with the payload counted on each. Peer workers run on
 * several threads, so every field is atomic.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RecordingConnections : ConnectionReporter {
  private val all = AtomicReference<List<Connection>>(emptyList())

  /** Every connection opened so far, oldest first. */
  val opened: List<Connection> get() = all.load()

  /** The connections not closed yet. */
  val open: List<Connection> get() = opened.filter { !it.closed }

  override fun open(spec: ConnectionSpec): ConnectionHandle {
    val connection = Connection(spec)
    while (true) {
      val current = all.load()
      if (all.compareAndSet(current, current + connection)) return connection
    }
  }

  class Connection(spec: ConnectionSpec) : ConnectionHandle {
    private val current = AtomicReference(spec)
    private val receivedBytes = AtomicLong(0)
    private val sentBytes = AtomicLong(0)
    private val closedFlag = AtomicBoolean(false)
    private val describeCount = AtomicInt(0)

    /** The latest description. */
    val spec: ConnectionSpec get() = current.load()
    val received: Long get() = receivedBytes.load()
    val sent: Long get() = sentBytes.load()
    val closed: Boolean get() = closedFlag.load()

    /** How many times the description changed. */
    val describes: Int get() = describeCount.load()

    override fun received(bytes: Int) {
      receivedBytes.addAndFetch(bytes.toLong())
    }

    override fun sent(bytes: Int) {
      sentBytes.addAndFetch(bytes.toLong())
    }

    override fun describe(spec: ConnectionSpec) {
      current.store(spec)
      describeCount.incrementAndFetch()
    }

    override fun close() {
      closedFlag.store(true)
    }
  }
}
