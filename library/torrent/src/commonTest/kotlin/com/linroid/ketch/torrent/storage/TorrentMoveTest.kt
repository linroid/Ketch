package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentMoveTest {
  /** A file system that cannot rename, as between two volumes, and may fail to copy [failing]. */
  private class NoRename(
    delegate: FileSystem,
    val failing: String? = null,
    val keeps: Boolean = false,
  ) : ForwardingFileSystem(delegate) {
    override fun delete(path: Path, mustExist: Boolean) {
      if (keeps) throw IOException("read-only")
      super.delete(path, mustExist)
    }

    override fun atomicMove(source: Path, target: Path): Unit = throw IOException("cross-device")

    override fun copy(source: Path, target: Path) {
      if (source.name == failing) throw IOException("disk full")
      super.copy(source, target)
    }
  }

  private fun tree(block: (root: Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-move-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    try {
      torrentFileSystem.createDirectories(root / "from" / "pack" / "inner")
      torrentFileSystem.write(root / "from" / "pack" / "a.bin") { write(byteArrayOf(1, 2, 3)) }
      torrentFileSystem.write(root / "from" / "pack" / "inner" / "b.bin") { write(ByteArray(0)) }
      torrentFileSystem.createDirectories(root / "to")
      block(root)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  private fun read(path: Path) = torrentFileSystem.read(path) { readByteArray() }

  @Test
  fun `a tree is renamed where it can be and copied where it cannot`() = tree { root ->
    moveTorrentTree(torrentFileSystem, root / "from" / "pack", root / "to" / "pack")
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "to" / "pack" / "a.bin"))
    assertNull(torrentFileSystem.metadataOrNull(root / "from" / "pack"))

    moveTorrentTree(NoRename(torrentFileSystem), root / "to" / "pack", root / "from" / "pack")
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "from" / "pack" / "a.bin"))
    assertEquals(0L, torrentFileSystem.metadata(root / "from" / "pack" / "inner" / "b.bin").size)
    assertNull(torrentFileSystem.metadataOrNull(root / "to" / "pack"))

    // A single file moves the same way.
    moveTorrentTree(NoRename(torrentFileSystem), root / "from" / "pack" / "a.bin",
      root / "to" / "a.bin")
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "to" / "a.bin"))
    assertNull(torrentFileSystem.metadataOrNull(root / "from" / "pack" / "a.bin"))
  }

  @Test
  fun `a copy that fails leaves the files where they were`() = tree { root ->
    assertFailsWith<IOException> {
      moveTorrentTree(NoRename(torrentFileSystem, failing = "b.bin"), root / "from" / "pack",
        root / "to" / "pack")
    }
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "from" / "pack" / "a.bin"))
    assertNull(torrentFileSystem.metadataOrNull(root / "to" / "pack"))
  }

  @Test
  fun `a copy whose original cannot be deleted still counts as moved`() = tree { root ->
    // The files are whole at their new place, so that is where the task saves from now on.
    assertFalse(moveTorrentTree(NoRename(torrentFileSystem, keeps = true), root / "from" / "pack",
      root / "to" / "pack"))
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "to" / "pack" / "a.bin"))
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "from" / "pack" / "a.bin"))
    assertTrue(moveTorrentTree(torrentFileSystem, root / "to" / "pack", root / "to" / "again"))
  }

  @Test
  fun `nothing is moved onto something that is there`() = tree { root ->
    torrentFileSystem.createDirectories(root / "to" / "pack")
    assertFailsWith<IllegalStateException> {
      moveTorrentTree(torrentFileSystem, root / "from" / "pack", root / "to" / "pack")
    }
    assertContentEquals(byteArrayOf(1, 2, 3), read(root / "from" / "pack" / "a.bin"))
  }
}
