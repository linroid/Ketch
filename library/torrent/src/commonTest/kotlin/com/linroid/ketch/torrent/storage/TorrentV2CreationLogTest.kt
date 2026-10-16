package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentV2CreationLogTest {
  private val binding = ByteArray(32) { it.toByte() }
  private val root = TorrentV2Checkpoint.Owned(emptyList(), "root-identity", directory = true)

  /** Publishes as on FAT, exFAT and some network shares, which refuse hard links. */
  private fun publishWithoutLinks(source: Path, target: Path): Boolean =
    torrentPublishCatalogObject(source, target, link = { _, _ ->
      throw IOException("Operation not supported")
    })

  private fun withDirectory(block: (Path) -> Unit) {
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-creation-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectory(directory)
    try {
      block(directory)
    } finally {
      torrentFileSystem.deleteRecursively(directory, mustExist = false)
    }
  }

  @Test
  fun open_withoutHardLinks_createsTheLogAndReadsItBack() = withDirectory { directory ->
    val path = directory / ".ketch-v2-task.creation"
    val log = TorrentV2CreationLog(path, torrentFileSystem, ::publishWithoutLinks)
    assertEquals(0, log.open(listOf(binding)).matched)
    log.append(root)

    val reopened = TorrentV2CreationLog(path, torrentFileSystem, ::publishWithoutLinks)
      .open(listOf(binding))

    assertEquals(listOf(root), reopened.records)
    // Only the log: the file it was written to became it.
    assertEquals(listOf(path), torrentFileSystem.list(directory))
  }

  @Test
  fun open_emptyLogAnInterruptedPublicationLeft_isCreatedAgain() = withDirectory { directory ->
    val path = directory / ".ketch-v2-task.creation"
    torrentFileSystem.write(path) {}

    val opened = TorrentV2CreationLog(path, torrentFileSystem, ::publishWithoutLinks)
      .open(listOf(binding))

    assertEquals(0, opened.matched)
    assertTrue(requireNotNull(torrentFileSystem.metadata(path).size) > 0)
  }
}
