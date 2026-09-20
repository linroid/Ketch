package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorrentContentCatalogTest {
  private val info = mapOf("meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("file" to mapOf("" to mapOf("length" to 1L,
      "pieces root" to sha256Digest(byteArrayOf(1))))))
  private fun document(context: String = "first") = TorrentV2Document.parse(Bencode.encode(mapOf(
    "announce" to "https://tracker.invalid/$context", "comment" to context,
    "info" to info, "piece layers" to emptyMap<String, Any>()
  )))
  private fun directory(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-catalog-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"

  @Test
  fun persistsAuthenticatedContentAcrossInstancesWithoutCallerContext() = runTest {
    val root = directory()
    val catalog = TorrentContentCatalog(root)
    val doc = document()
    try {
      assertNull(catalog.get(doc.identity))
      assertEquals(doc.identity, catalog.put(doc))
      assertEquals(doc.identity, catalog.put(document("other")))
      val reopened = TorrentContentCatalog(root)
      val loaded = assertNotNull(reopened.get(doc.identity))
      assertContentEquals(doc.info.rawInfo.toByteArray(), loaded.info.rawInfo.toByteArray())
      val files = torrentFileSystem.list(root)
      assertEquals(1, files.size)
      val persisted = Bencode.parse(torrentFileSystem.read(files.single()) { readByteArray() })
      assertNull(persisted["announce"])
      assertNull(persisted["comment"])
      assertEquals(setOf("info", "piece layers"),
        persisted.dictionary?.keys?.map { it.utf8() }?.toSet())
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun corruptExistingObjectIsNeverOverwritten() = runTest {
    val root = directory()
    val doc = document()
    torrentFileSystem.createDirectory(root)
    val target = root / "${doc.info.hash.hex}.torrent"
    val foreign = "existing data".encodeToByteArray()
    torrentFileSystem.write(target) { write(foreign) }
    val catalog = TorrentContentCatalog(root)
    try {
      assertFailsWith<IllegalArgumentException> { catalog.get(doc.identity) }
      assertFailsWith<IllegalArgumentException> { catalog.put(doc) }
      assertContentEquals(foreign, torrentFileSystem.read(target) { readByteArray() })
      assertEquals(1, torrentFileSystem.list(root).size)
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }

  @Test
  fun authenticatesHybridTopicsAndExternalLayersAfterReopening() = runTest {
    val first = ByteArray(16_384)
    val last = byteArrayOf(1)
    val layer = sha256Digest(first) + sha256Digest(last)
    val hash = sha256Digest(layer).toByteString()
    val hybridInfo = mapOf("meta version" to 2L, "piece length" to 16_384L,
      "name" to "file", "length" to 16_385L,
      "pieces" to (sha1Digest(first) + sha1Digest(last)),
      "file tree" to mapOf("file" to mapOf("" to mapOf("length" to 16_385L,
        "pieces root" to hash))))
    val doc = TorrentV2Document.parse(Bencode.encode(mapOf("info" to hybridInfo,
      "piece layers" to mapOf(hash to layer))))
    val root = directory()
    try {
      TorrentContentCatalog(root).put(doc)
      val reopened = TorrentContentCatalog(root)
      val loaded = assertNotNull(reopened.get(doc.identity))
      assertEquals(doc.identity, loaded.identity)
      assertEquals(layer.toByteString(), loaded.pieceLayers[hash])
      assertFailsWith<IllegalArgumentException> {
        reopened.get(TorrentIdentity(InfoHash.fromBytes(ByteArray(20)), doc.info.hash))
      }
      torrentFileSystem.write(root / "${doc.info.hash.hex}.torrent") {
        write(Bencode.encode(mapOf("info" to hybridInfo,
          "piece layers" to mapOf(hash to ByteArray(64)))))
      }
      assertFailsWith<IllegalArgumentException> { reopened.get(doc.identity) }
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun failedPublicationRemovesOnlyItsTemporaryObjectAndCanRetry() = runTest {
    val root = directory()
    var fail = true
    val catalog = TorrentContentCatalog(root, publish = { source, target ->
      if (fail) throw IOException("Injected publication failure")
      torrentPublishCatalogObject(source, target)
    })
    try {
      assertFailsWith<IOException> { catalog.put(document()) }
      assertEquals(emptyList(), torrentFileSystem.list(root))
      assertNull(catalog.get(document().identity))
      fail = false
      catalog.put(document())
      assertNotNull(catalog.get(document().identity))
      assertEquals(1, torrentFileSystem.list(root).size)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun targetAppearingDuringPublicationIsAuthenticatedWithoutReplacement() = runTest {
    for (valid in listOf(false, true)) {
      val root = directory()
      val doc = document()
      val winner = if (valid) Bencode.encode(mapOf("info" to info,
        "piece layers" to emptyMap<String, Any>())) else "competing data".encodeToByteArray()
      val catalog = TorrentContentCatalog(root, publish = { source, target ->
        torrentFileSystem.write(target, mustCreate = true) { write(winner) }
        assertFalse(torrentPublishCatalogObject(source, target))
        false
      })
      try {
        if (valid) assertEquals(doc.identity, catalog.put(doc)) else {
          assertFailsWith<IllegalArgumentException> { catalog.put(doc) }
        }
        val target = root / "${doc.info.hash.hex}.torrent"
        assertContentEquals(winner, torrentFileSystem.read(target) { readByteArray() })
        assertEquals(1, torrentFileSystem.list(root).size)
      } finally {
        torrentFileSystem.deleteRecursively(root, mustExist = false)
      }
    }
  }

  @Test
  fun rejectsOversizedObjectsBeforeLoadingAndDoesNotConfuseV1Topics() = runTest {
    val root = directory()
    val doc = document()
    val catalog = TorrentContentCatalog(root)
    try {
      catalog.put(doc)
      val target = root / "${doc.info.hash.hex}.torrent"
      torrentFileSystem.openReadWrite(target, mustExist = true).use {
        it.resize(TorrentContentCatalog.MAX_BYTES.toLong() + 1)
      }
      assertFailsWith<IllegalArgumentException> { catalog.get(doc.identity) }
      assertFailsWith<IllegalArgumentException> {
        catalog.get(TorrentIdentity(v1 = InfoHash.fromBytes(ByteArray(20))))
      }
      assertFalse(torrentFileSystem.list(root).any { it.name.endsWith(".tmp") })
    } finally {
      torrentFileSystem.deleteRecursively(root)
    }
  }
}
