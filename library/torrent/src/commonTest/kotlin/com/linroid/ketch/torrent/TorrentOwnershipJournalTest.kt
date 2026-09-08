package com.linroid.ketch.torrent

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
