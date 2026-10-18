package com.linroid.ketch.metalink

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.digest.sha256Digest
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Metalink downloads through a whole [Ketch] instance, onto disk. */
class MetalinkKetchTest {
  private val folder: File = Files.createTempDirectory("ketch-metalink").toFile()
  private val iso = ByteArray(300_000) { (it * 7 % 253).toByte() }
  private val notes = "release notes".encodeToByteArray()

  @AfterTest
  fun cleanUp() {
    folder.deleteRecursively()
  }

  private fun hex(bytes: ByteArray) =
    Checksum.of(ChecksumAlgorithm.SHA_256, sha256Digest(bytes)).value

  private fun document(isoHash: String = hex(iso)) = """
    <metalink xmlns="urn:ietf:params:xml:ns:metalink">
      <file name="release.iso">
        <size>${iso.size}</size>
        <hash type="sha-256">$isoHash</hash>
        <url priority="1">https://a.example/release.iso</url>
        <url priority="2">https://b.example/release.iso</url>
        <url priority="3">https://c.example/release.iso</url>
      </file>
      <file name="NOTES.txt">
        <size>${notes.size}</size>
        <url>https://a.example/NOTES.txt</url>
      </file>
    </metalink>
  """

  private fun ketch(engine: HttpEngine) = Ketch(
    httpEngine = engine,
    config = DownloadConfig(retryCount = 0, maxConnectionsPerDownload = 4),
    additionalSources = listOf(MetalinkDownloadSource(engine)),
  )

  private suspend fun finish(ketch: Ketch, request: DownloadRequest): DownloadState =
    ketch.download(request).state.first { it.isTerminal }

  @Test
  fun download_completesFromEveryMirrorAndVerifiesTheChecksum() = runBlocking {
    val engine = Engine(document())
    val ketch = ketch(engine)
    try {
      assertTrue(KetchFeatures.METALINK in ketch.status().features)
      val state = finish(ketch, DownloadRequest("https://example.com/release.meta4",
        destination = Destination(folder.path + "/")))

      val completed = assertIs<DownloadState.Completed>(state)
      assertEquals(File(folder, "release.iso").path, completed.outputPath)
      assertContentEquals(iso, File(folder, "release.iso").readBytes())
      assertEquals(Checksum(ChecksumAlgorithm.SHA_256, hex(iso)), completed.checksum)
      assertEquals(3, engine.mirrorsAsked.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_chosenFile_isSavedUnderItsOwnName() = runBlocking {
    val ketch = ketch(Engine(document()))
    try {
      val state = finish(ketch, DownloadRequest("https://example.com/release.meta4",
        destination = Destination(folder.path + "/"), selectedFileIds = setOf("1")))

      val completed = assertIs<DownloadState.Completed>(state)
      assertEquals(File(folder, "NOTES.txt").path, completed.outputPath)
      assertContentEquals(notes, File(folder, "NOTES.txt").readBytes())
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_wrongWholeFileHash_failsAsAChecksumMismatch() = runBlocking {
    val ketch = ketch(Engine(document(isoHash = "0".repeat(64))))
    try {
      val state = finish(ketch, DownloadRequest("https://example.com/release.meta4",
        destination = Destination(folder.path + "/")))

      val error = assertIs<DownloadState.Failed>(state).error
      assertIs<KetchError.ChecksumMismatch>(error)
      assertTrue(error.published)
      assertTrue(folder.listFiles().orEmpty().none { it.name == "release.iso" })
    } finally {
      ketch.close()
    }
  }

  private inner class Engine(private val document: String) : HttpEngine {
    val mirrorsAsked = mutableSetOf<String>()

    private fun content(url: String) = if (url.endsWith("NOTES.txt")) notes else iso

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      ServerInfo(
        contentLength = content(url).size.toLong(), acceptRanges = true, etag = null,
        lastModified = null,
      )

    override suspend fun downloadResource(
      url: String,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): String {
      onData(document.encodeToByteArray())
      return url
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      synchronized(mirrorsAsked) { mirrorsAsked += url }
      val bytes = content(url)
      val wanted = range ?: 0L..bytes.lastIndex.toLong()
      var offset = wanted.first.toInt()
      while (offset <= wanted.last) {
        val end = minOf(offset + 8192, wanted.last.toInt() + 1)
        onData(bytes.copyOfRange(offset, end))
        offset = end
      }
    }

    override fun close() {}
  }
}
