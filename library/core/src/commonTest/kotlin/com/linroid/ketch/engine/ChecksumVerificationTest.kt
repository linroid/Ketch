package com.linroid.ketch.engine

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.digest.newDigest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.engine.fileDigest
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.core.file.platformFileSystem
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChecksumVerificationTest {
  private val content = ByteArray(1000) { (it % 256).toByte() }
  private val sha256 = checksumOf(ChecksumAlgorithm.SHA_256, content)
  private val wrong = Checksum(ChecksumAlgorithm.SHA_256, "0".repeat(64))

  @Test
  fun download_matchingChecksum_completesWithIt() = runTest {
    withKetch(FakeHttpEngine(content = content)) { ketch, folder, _ ->
      val task = ketch.download(request(folder, checksum = sha256))

      val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals(sha256, completed.checksum)
      assertContentEquals(content, platformFileSystem.read(output(folder)) { readByteArray() })
    }
  }

  @Test
  fun download_md5AndSha512_areVerifiedToo() = runTest {
    for (algorithm in listOf(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA_512)) {
      withKetch(FakeHttpEngine(content = content)) { ketch, folder, _ ->
        val expected = checksumOf(algorithm, content)
        val task = ketch.download(request(folder, checksum = expected))

        val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
        assertEquals(expected, completed.checksum)
      }
    }
  }

  @Test
  fun download_mismatch_failsDeletesFileAndDiscardsProgress() = runTest {
    val engine = FakeHttpEngine(content = content)
    withKetch(engine) { ketch, folder, store ->
      val task = ketch.download(request(folder, checksum = wrong))

      val failed = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      val error = assertIs<KetchError.ChecksumMismatch>(failed.error)
      assertEquals(ChecksumAlgorithm.SHA_256, error.algorithm)
      assertEquals(wrong.value, error.expected)
      assertEquals(sha256.value, error.actual)
      assertFalse(error.published)
      // Checked before the move, so neither the final name nor the partial file holds it.
      assertFalse(platformFileSystem.exists(output(folder)))
      assertFalse(platformFileSystem.exists(output(folder, "file.bin.ketch")))
      val segments = store.load(task.taskId)?.segments.orEmpty()
      assertTrue(segments.isNotEmpty() && segments.all { it.downloadedBytes == 0L })

      // A resume downloads every byte again rather than trusting the deleted file.
      val requests = engine.downloadCallCount
      task.resume()
      task.state.first { it is DownloadState.Failed && engine.downloadCallCount > requests }
    }
  }

  @Test
  fun download_withoutChecksum_verifiesAgainstPublishedDigest() = runTest {
    val engine = FakeHttpEngine(content = content, serverInfo = serverInfo(sha256))
    withKetch(engine) { ketch, folder, _ ->
      val task = ketch.download(request(folder))

      val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals(sha256, completed.checksum)
    }
  }

  @Test
  fun download_publishedDigestMismatch_failsAsPublished() = runTest {
    val engine = FakeHttpEngine(content = content, serverInfo = serverInfo(wrong))
    withKetch(engine) { ketch, folder, _ ->
      val task = ketch.download(request(folder))

      val failed = assertIs<DownloadState.Failed>(task.state.first { it.isTerminal })
      assertTrue(assertIs<KetchError.ChecksumMismatch>(failed.error).published)
    }
  }

  @Test
  fun download_requestChecksum_takesPrecedenceOverPublished() = runTest {
    val engine = FakeHttpEngine(content = content, serverInfo = serverInfo(wrong))
    withKetch(engine) { ketch, folder, _ ->
      val task = ketch.download(request(folder, checksum = sha256))

      val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals(sha256, completed.checksum)
    }
  }

  @Test
  fun download_nothingToVerify_completesWithoutChecksum() = runTest {
    withKetch(FakeHttpEngine(content = content)) { ketch, folder, _ ->
      val task = ketch.download(request(folder))

      val completed = assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals(null, completed.checksum)
    }
  }

  @Test
  fun download_emptyFile_checksOnlyTheEmptyDigest() = runTest {
    val empty = ByteArray(0)
    val engine = FakeHttpEngine(content = empty, serverInfo = serverInfo(null, length = 0))
    withKetch(engine) { ketch, folder, _ ->
      val matching = ketch.download(
        request(folder, checksum = checksumOf(ChecksumAlgorithm.SHA_1, empty), name = "a.bin"),
      )
      assertIs<DownloadState.Completed>(matching.state.first { it.isTerminal })

      val mismatching = ketch.download(request(folder, checksum = sha256, name = "b.bin"))
      val failed = assertIs<DownloadState.Failed>(mismatching.state.first { it.isTerminal })
      assertIs<KetchError.ChecksumMismatch>(failed.error)
    }
  }

  @Test
  fun download_checksumForSelfManagedSource_isRefused() = runTest {
    withKetch(FakeHttpEngine(), listOf(SelfManagedSource())) { ketch, folder, _ ->
      assertFailsWith<IllegalArgumentException> {
        ketch.download(DownloadRequest("self:file", checksum = sha256))
      }
      assertTrue(KetchFeatures.CHECKSUM in ketch.status().features)
      // The same request without a checksum is accepted.
      ketch.download(DownloadRequest("self:file", destination = Destination("$folder/")))
    }
  }

  @Test
  fun fileDigest_cancelled_stopsReading() = runTest {
    val reading = CompletableDeferred<Unit>()
    var reads = 0
    val file = object : FileAccessor {
      override suspend fun readAt(
        offset: Long,
        buffer: ByteArray,
        bufferOffset: Int,
        byteCount: Int,
      ): Int {
        reads++
        if (reads == 2) {
          reading.complete(Unit)
          awaitCancellation()
        }
        return byteCount
      }
      override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
      override suspend fun flush() = Unit
      override fun close() = Unit
      override suspend fun delete() = Unit
      override suspend fun size(): Long = Long.MAX_VALUE
      override suspend fun preallocate(size: Long) = Unit
    }

    val job = launch { fileDigest(file, ChecksumAlgorithm.SHA_256, 10L * 1024 * 1024) }
    reading.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertEquals(2, reads)
  }

  private fun checksumOf(algorithm: ChecksumAlgorithm, bytes: ByteArray): Checksum =
    Checksum.of(algorithm, algorithm.newDigest().update(bytes).digest())

  private fun serverInfo(checksum: Checksum?, length: Long = content.size.toLong()) = ServerInfo(
    contentLength = length,
    acceptRanges = true,
    etag = "\"etag\"",
    lastModified = null,
    checksum = checksum,
  )

  private fun output(folder: String, name: String = "file.bin") = "$folder/$name".toPath()

  private fun request(
    folder: String,
    checksum: Checksum? = null,
    name: String = "file.bin",
  ) = DownloadRequest(
    url = "https://example.com/$name",
    destination = Destination("$folder/$name"),
    connections = 4,
    checksum = checksum,
  )

  private suspend fun TestScope.withKetch(
    engine: FakeHttpEngine,
    sources: List<DownloadSource> = emptyList(),
    block: suspend (Ketch, String, InMemoryTaskStore) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-checksum-test"
    platformFileSystem.deleteRecursively(folder)
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val store = InMemoryTaskStore()
    val ketch = Ketch(
      httpEngine = engine,
      taskStore = store,
      config = DownloadConfig(defaultDirectory = folder.toString(), retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      additionalSources = sources,
    )
    try {
      block(ketch, folder.toString(), store)
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  /** A source that writes its own files, as the torrent source does. */
  private class SelfManagedSource : DownloadSource {
    override val type = "self"
    override val managesOwnFileIo = true
    override fun canHandle(url: String) = url.startsWith("self:")
    override suspend fun resolve(url: String, headers: Map<String, String>) = ResolvedSource(
      url = url, sourceType = type, totalBytes = 1, supportsResume = false,
      suggestedFileName = "self", maxSegments = 1,
    )
    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long) =
      SourceResumeState(type, "")
    override suspend fun download(context: DownloadContext) = Unit
    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) = Unit
  }
}
