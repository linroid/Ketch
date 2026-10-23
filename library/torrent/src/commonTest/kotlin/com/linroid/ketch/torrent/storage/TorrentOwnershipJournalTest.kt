package com.linroid.ketch.torrent.storage

import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TorrentOwnershipJournalTest {
  @Test
  fun compactionAtomicallyReplacesStaleRecordsAndRetainsItsOwnIdentity() {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-journal-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root)
    try {
      val path = root / "ownership"
      val binding = "task-binding".encodeToByteArray()
      val journal = TorrentOwnershipJournal(path, binding, torrentFileSystem)
      journal.initialize()
      val before = torrentFileIdentity(path)
      val old = TorrentOwnedPath((root / "old").toString(), "old-identity")
      val current = TorrentOwnedPath((root / "current").toString(), "current-identity")
      journal.append(false, old)
      journal.append(false, current)
      val identity = journal.compact(listOf(false to current))
      assertNotEquals(before, identity)
      assertEquals(torrentFileIdentity(path), identity)
      val restored = TorrentOwnershipJournal(path, binding, torrentFileSystem).load()
      assertTrue(restored.none { it.second == old })
      assertEquals(setOf(current, TorrentOwnedPath(path.toString(), identity)),
        restored.map { it.second }.toSet())
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun legacyBindingLoadsAndCompactionRebindsIt() {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-journal-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    torrentFileSystem.createDirectories(root)
    try {
      val path = root / "ownership"
      val legacy = "legacy-binding".encodeToByteArray()
      val current = "current-binding".encodeToByteArray()
      val owned = TorrentOwnedPath((root / "file").toString(), "file-identity")
      TorrentOwnershipJournal(path, legacy, torrentFileSystem).apply {
        initialize()
        append(false, owned)
      }

      val journal = TorrentOwnershipJournal(path, current, torrentFileSystem, listOf(legacy))
      assertEquals(listOf(owned), journal.load().map { it.second })
      journal.compact(listOf(false to owned))

      // Compacted, it is bound to the current binding alone.
      assertTrue(TorrentOwnershipJournal(path, current, torrentFileSystem).load().isNotEmpty())
      assertFailsWith<IllegalArgumentException> {
        TorrentOwnershipJournal(path, legacy, torrentFileSystem).load()
      }
      assertFailsWith<IllegalArgumentException> {
        TorrentOwnershipJournal(path, "other".encodeToByteArray(), torrentFileSystem).load()
      }
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }
}
