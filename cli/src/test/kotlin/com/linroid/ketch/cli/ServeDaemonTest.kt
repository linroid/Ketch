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
