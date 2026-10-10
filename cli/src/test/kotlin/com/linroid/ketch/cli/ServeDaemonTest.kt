package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import com.linroid.ketch.core.task.TaskStore
import com.linroid.ketch.server.KetchServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

class ServeDaemonTest {

  @Test
  fun `serveDaemon restores the tasks saved by an earlier run`() {
    val ketch = Ketch(UnreachableHttpEngine(), taskStore = SavedTaskStore(pausedRecord("saved")))
    val server = KetchServer(ketch, host = "127.0.0.1", port = 0, mdnsEnabled = false)
    val daemon = thread { serveDaemon(server, ketch) }
    try {
      val restored = runBlocking {
        withTimeout(5.seconds) { ketch.tasks.first { it.isNotEmpty() } }
      }
      assertEquals(listOf("saved"), restored.map { it.taskId })
      assertIs<DownloadState.Paused>(restored.single().state.value)
      assertTrue(daemon.isAlive)
    } finally {
      server.stop()
      daemon.join(5_000)
      ketch.close()
    }
    assertFalse(daemon.isAlive)
  }

  // Clients that find the server through what onReady publishes must see every task.
  @Test
  fun `serveDaemon is ready once it listens and has restored the tasks`() {
    val ketch = Ketch(UnreachableHttpEngine(), taskStore = SavedTaskStore(pausedRecord("saved")))
    val server = KetchServer(ketch, host = "127.0.0.1", port = 0, mdnsEnabled = false)
    val ready = CompletableDeferred<Pair<Int, Int>>()
    val daemon = thread {
      serveDaemon(server, ketch) {
        ready.complete(runBlocking { server.port() } to ketch.tasks.value.size)
      }
    }
    try {
      val (port, tasks) = runBlocking { withTimeout(5.seconds) { ready.await() } }
      assertTrue(port > 0)
      assertEquals(1, tasks)
    } finally {
      server.stop()
      daemon.join(5_000)
      ketch.close()
    }
  }

  @Test
  fun `loopbackUrl reaches a server on every interface through loopback`() {
    assertEquals("http://127.0.0.1:8642", loopbackUrl("0.0.0.0", 8642))
    assertEquals("http://127.0.0.1:8642", loopbackUrl("::", 8642))
    assertEquals("http://[::1]:9000", loopbackUrl("::1", 9000))
    assertEquals("http://192.168.1.20:8642", loopbackUrl("192.168.1.20", 8642))
  }

  @Test
  fun `serveDaemon reports ready once the saved tasks are restored`() {
    val restore = CompletableDeferred<Unit>()
    val store = GatedTaskStore(restore, SavedTaskStore(pausedRecord("saved")))
    val ketch = Ketch(UnreachableHttpEngine(), taskStore = store)
    val server =
      KetchServer(ketch, host = "127.0.0.1", port = 0, mdnsEnabled = false, ready = false)
    val daemon = thread { serveDaemon(server, ketch) }
    try {
      val url = healthUrl("127.0.0.1", runBlocking { withTimeout(5.seconds) { server.port() } })
      assertEquals(HealthExit.NOT_READY, checkHealth(url))

      restore.complete(Unit)
      val deadline = TimeSource.Monotonic.markNow() + 5.seconds
      while (checkHealth(url) != HealthExit.READY) {
        assertTrue(deadline.hasNotPassedNow(), "the server never reported ready")
        Thread.sleep(50)
      }
      assertEquals(listOf("saved"), ketch.tasks.value.map { it.taskId })
    } finally {
      server.stop()
      daemon.join(5_000)
      ketch.close()
    }
  }

  @Test
  fun `serveDaemon restores no tasks when the server cannot listen`() {
    val loopback = InetAddress.getLoopbackAddress()
    ServerSocket(0, 1, loopback).use { taken ->
      val ketch = Ketch(UnreachableHttpEngine(), taskStore = SavedTaskStore(pausedRecord("saved")))
      val server = KetchServer(
        ketch,
        host = loopback.hostAddress,
        port = taken.localPort,
        mdnsEnabled = false,
      )
      try {
        assertFails { serveDaemon(server, ketch) }
        assertTrue(ketch.tasks.value.isEmpty())
      } finally {
        server.stop()
        ketch.close()
      }
    }
  }
}

private fun pausedRecord(taskId: String): TaskRecord {
  val savedAt = Instant.parse("2026-10-01T00:00:00Z")
  return TaskRecord(
    taskId = taskId,
    request = DownloadRequest(url = "https://example.com/$taskId.zip"),
    state = TaskState.PAUSED,
    totalBytes = 100,
    createdAt = savedAt,
    updatedAt = savedAt,
  )
}

/** A [TaskStore] holding the records an earlier run of the daemon saved. */
private class SavedTaskStore(vararg records: TaskRecord) : TaskStore {
  private val records = ConcurrentHashMap(records.associateBy { it.taskId })

  override suspend fun save(record: TaskRecord) {
    records[record.taskId] = record
  }

  override suspend fun load(taskId: String): TaskRecord? = records[taskId]

  override suspend fun loadAll(): List<TaskRecord> = records.values.toList()

  override suspend fun remove(taskId: String) {
    records.remove(taskId)
  }
}

/** A [TaskStore] whose saved records only load once [gate] completes. */
private class GatedTaskStore(
  private val gate: CompletableDeferred<Unit>,
  private val delegate: TaskStore,
) : TaskStore by delegate {
  override suspend fun loadAll(): List<TaskRecord> {
    gate.await()
    return delegate.loadAll()
  }
}

/** Fails every request; restoring paused tasks must not reach the network. */
private class UnreachableHttpEngine : HttpEngine {
  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
    throw KetchError.Network(IllegalStateException("No network in tests"))
  }

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    throw KetchError.Network(IllegalStateException("No network in tests"))
  }

  override fun close() {}
}
