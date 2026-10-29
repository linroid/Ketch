package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeTorrentFileSystemTest {
  private fun withRoot(block: (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-safe-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    FileSystem.SYSTEM.createDirectories(root)
    try {
      block(root)
    } finally {
      FileSystem.SYSTEM.deleteRecursively(root)
    }
  }

  @Test
  fun writesAndReadsInsideNestedFolders() = withRoot { root ->
    val nested = root / "a" / "b"
    SafeTorrentFileSystem.createDirectory(root / "a", mustCreate = true)
    SafeTorrentFileSystem.createDirectory(nested, mustCreate = true)
    val file = nested / "payload"
    SafeTorrentFileSystem.openReadWrite(file, mustCreate = true).use { handle ->
      handle.write(0, byteArrayOf(1, 2, 3), 0, 3)
      handle.resize(2)
    }
    SafeTorrentFileSystem.openReadOnly(file).use { handle ->
      val bytes = ByteArray(2)
      assertEquals(2, handle.read(0, bytes, 0, 2))
      assertEquals(listOf<Byte>(1, 2), bytes.toList())
      assertEquals(2L, handle.size())
    }
    SafeTorrentFileSystem.atomicMove(file, nested / "moved")
    SafeTorrentFileSystem.delete(nested / "moved", mustExist = true)
    assertTrue(FileSystem.SYSTEM.list(nested).isEmpty())
  }

  @Test
  fun symlinkedAncestor_isRefusedWithoutTouchingTarget() = withRoot { root ->
    FileSystem.SYSTEM.createDirectories(root / "real" / "sub")
    FileSystem.SYSTEM.createSymlink(root / "link", root / "real")
    val inside = root / "link" / "sub"
    assertFailsWith<IOException> {
      SafeTorrentFileSystem.openReadWrite(inside / "file", mustCreate = true).close()
    }
    assertFailsWith<IOException> { SafeTorrentFileSystem.createDirectory(inside / "dir") }
    assertFailsWith<IOException> { SafeTorrentFileSystem.openReadOnly(inside / "file") }
    assertTrue(FileSystem.SYSTEM.list(root / "real" / "sub").isEmpty())
  }

  @Test
  fun symlinkedName_isRefused() = withRoot { root ->
    FileSystem.SYSTEM.write(root / "target") { writeUtf8("kept") }
    FileSystem.SYSTEM.createSymlink(root / "alias", root / "target")
    assertFailsWith<IOException> { SafeTorrentFileSystem.openReadWrite(root / "alias").close() }
    assertEquals("kept", FileSystem.SYSTEM.read(root / "target") { readUtf8() })
  }
}
