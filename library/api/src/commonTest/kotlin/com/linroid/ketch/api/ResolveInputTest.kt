package com.linroid.ketch.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration

class ResolveInputTest {
  @Test
  fun content_comparesByItsBytesAndName() {
    val content = ResolveInput.Content(byteArrayOf(1, 2), "a.torrent")
    assertEquals(content, ResolveInput.Content(byteArrayOf(1, 2), "a.torrent"))
    val same = ResolveInput.Content(byteArrayOf(1, 2), "a.torrent")
    assertEquals(content.hashCode(), same.hashCode())
    assertNotEquals(content, ResolveInput.Content(byteArrayOf(1, 3), "a.torrent"))
    assertNotEquals(content, ResolveInput.Content(byteArrayOf(1, 2), "b.torrent"))
  }

  @Test
  fun content_toStringLeavesTheBytesOut() {
    assertEquals(
      "Content(fileName=a.torrent, size=2)",
      ResolveInput.Content(byteArrayOf(1, 2), "a.torrent").toString(),
    )
  }

  @Test
  fun extensions_buildTheInputs() = runTest {
    val inputs = mutableListOf<ResolveInput>()
    val api = RecordingResolver(inputs)
    api.resolve("https://example.com/a.iso", mapOf("Cookie" to "x"))
    api.resolve(byteArrayOf(7), "a.torrent")
    assertEquals(
      listOf(
        ResolveInput.Url("https://example.com/a.iso", mapOf("Cookie" to "x")),
        ResolveInput.Content(byteArrayOf(7), "a.torrent"),
      ),
      inputs,
    )
  }

  /** A [KetchApi] that only records what it is asked to resolve. */
  private class RecordingResolver(private val inputs: MutableList<ResolveInput>) : KetchApi {
    override val backendLabel = "Recording"
    override val tasks: StateFlow<List<DownloadTask>> = MutableStateFlow(emptyList())

    override suspend fun resolve(input: ResolveInput): ResolvedSource {
      inputs += input
      return ResolvedSource("x", "test", 0, false, null, 1)
    }

    override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> = emptyFlow()
    override suspend fun start() = Unit
    override fun close() = Unit
    override suspend fun download(request: DownloadRequest): DownloadTask = error("Unused")
    override suspend fun status(): KetchStatus = error("Unused")
    override suspend fun updateConfig(config: DownloadConfig) = Unit
  }
}
