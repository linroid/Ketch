package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.FileConflict
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.torrent.control.UnusedHttpEngine
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.storage.torrentSystemFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A download asked to use the files already in place ([FileConflict.OVERWRITE]) takes a folder
 * that holds the torrent's content, proves it and completes without a peer, in every format;
 * without the request it saves beside it, as before.
 */
class TorrentAdoptFilesTest {
  private val files = mapOf("a.bin" to 100_000, "b.bin" to 16_384, "sub/c.bin" to 40_000)

  private fun payload(size: Int) = ByteArray(size) { (it * 31 + size + it / 257).toByte() }

  private class Harness(val root: Path, val ketch: Ketch, val source: TorrentDownloadSource)

  private fun harness(block: suspend Harness.() -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(45_000) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-adopt-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val engines = mutableListOf<KotlinTorrentEngine>()
        val http = UnusedHttpEngine()
        val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.DISABLED, portMapping = false,
          localDiscovery = false, trackerListsEnabled = false), http)
        source.engineFactory = { config ->
          KotlinTorrentEngine(config, CountingTorrentNetwork(), TorrentHttp(http),
            listenHost = "127.0.0.1").also { engines += it }
        }
        val ketch = Ketch(http, taskStore = MemoryTaskStore(), additionalSources = listOf(source))
        try {
          ketch.start()
          Harness(root, ketch, source).block()
        } finally {
          ketch.close()
          engines.forEach { it.stop() }
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }

  private fun Harness.writeContent() {
    for ((name, size) in files) {
      val path = name.split('/').fold(root / "pack") { parent, part -> parent / part }
      torrentFileSystem.createDirectories(checkNotNull(path.parent))
      torrentFileSystem.write(path) { write(payload(size)) }
    }
  }

  private suspend fun Harness.add(
    format: TorrentFormat,
    conflict: FileConflict?,
    content: Path = root / "pack",
    into: Path = root,
  ): DownloadTask {
    val created = createTorrent(torrentSystemFileSystem, content,
      TorrentCreateOptions(format = format, pieceLength = 16_384, createdBy = null)) { _, _ -> }
    val resolved = ketch.resolve(ResolveInput.Content(created.metainfo, "pack.torrent"))
    return ketch.download(DownloadRequest(
      url = resolved.url,
      destination = Destination("$into/"),
      resolvedSource = resolved,
      fileConflict = conflict,
    ))
  }

  private fun adopts(format: TorrentFormat) = harness {
    writeContent()

    val task = add(format, FileConflict.OVERWRITE)

    val completed = assertIs<DownloadState.Completed>(
      task.state.first { it is DownloadState.Completed || it is DownloadState.Failed }
    )
    assertEquals((root / "pack").toString(), completed.outputPath)
    assertEquals(files.values.sum().toLong(), completed.totalBytes)
    // Nothing was downloaded, and the files are as they were.
    for ((name, size) in files) {
      val path = name.split('/').fold(root / "pack") { parent, part -> parent / part }
      assertContentEquals(payload(size), torrentFileSystem.read(path) { readByteArray() })
    }
    assertTrue(torrentFileSystem.metadataOrNull(root / "pack (1)") == null)
  }

  @Test fun `a v1 torrent uses the files already in place`() = adopts(TorrentFormat.V1)

  @Test fun `a v2 torrent uses the files already in place`() = adopts(TorrentFormat.V2)

  @Test fun `a hybrid torrent uses the files already in place`() = adopts(TorrentFormat.HYBRID)

  @Test
  fun `a folder that holds another unfinished download is not taken`() = harness {
    writeContent()
    torrentFileSystem.createDirectories(root / "other")
    torrentFileSystem.write(root / "other" / "x.bin") { write(payload(30_000)) }
    // No peer has it, so it stays unfinished, inside the folder the next torrent names.
    val inside = add(TorrentFormat.V1, conflict = null, content = root / "other",
      into = root / "pack" / "sub")
    inside.state.first { it is DownloadState.Downloading }
    while (inside.outputPath == null) delay(20)

    val task = add(TorrentFormat.V2, FileConflict.OVERWRITE)

    task.state.first { it is DownloadState.Downloading }
    while (task.outputPath == null) delay(20)
    assertEquals((root / "pack (1)").toString(), task.outputPath)
    task.cancel()
    inside.cancel()
  }

  @Test
  fun `a single file is used by a v1 torrent and left alone by a v2 one`() = harness {
    val file = root / "movie.mkv"
    torrentFileSystem.write(file) { write(payload(70_000)) }

    val v1 = add(TorrentFormat.V1, FileConflict.OVERWRITE, content = file)
    val completed = assertIs<DownloadState.Completed>(
      v1.state.first { it is DownloadState.Completed || it is DownloadState.Failed }
    )
    assertEquals(file.toString(), completed.outputPath)
    v1.remove(deleteFiles = false)

    // A v2 torrent keeps its file inside a folder of its name, which a file is in the way of.
    val v2 = add(TorrentFormat.V2, FileConflict.OVERWRITE, content = file)
    val failed = assertIs<DownloadState.Failed>(
      v2.state.first { it is DownloadState.Completed || it is DownloadState.Failed }
    )
    assertTrue("inside a folder" in failed.error.describeCauses(), failed.error.describeCauses())
    assertContentEquals(payload(70_000), torrentFileSystem.read(file) { readByteArray() })
  }

  @Test
  fun `without the request a torrent saves beside what is there`() = harness {
    writeContent()

    val task = add(TorrentFormat.V2, conflict = null)

    // No peer has it, so it only starts: in a folder of its own, leaving the files alone.
    task.state.first { it is DownloadState.Downloading }
    while (task.outputPath == null) delay(20)
    assertEquals((root / "pack (1)").toString(), task.outputPath)
    assertContentEquals(payload(100_000),
      torrentFileSystem.read(root / "pack" / "a.bin") { readByteArray() })
    task.cancel()
  }
}
